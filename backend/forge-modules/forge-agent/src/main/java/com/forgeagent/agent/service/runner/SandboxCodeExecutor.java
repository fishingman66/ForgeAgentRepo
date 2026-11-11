package com.forgeagent.agent.service.runner;

import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Docker 沙箱代码执行器
 *
 * <p>对话工具(DockerSandboxTool)与工作流代码节点共用的真实执行组件:
 * 在一次性隔离容器内执行 Python 代码,安全约束硬编码为
 * 无网络(--network=none)、内存/CPU 限额、进程数限额、执行超时、输出截断,
 * 执行结束容器自动销毁(--rm),宿主机临时文件清理。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class SandboxCodeExecutor {

    /**
     * 输出最大保留字符(超出截断)
     */
    private static final int MAX_OUTPUT = 8000;

    /**
     * 输出最多读取字符数:超出部分继续读空但不再缓存,避免管道写满阻塞子进程
     */
    private static final int MAX_READ_CHARS = 64_000;

    /**
     * 容器内最大进程数
     */
    private static final String PIDS_LIMIT = "64";

    /**
     * 全局并发执行上限:避免大量会话同时拉起容器打爆宿主机
     */
    private static final java.util.concurrent.Semaphore SLOTS = new java.util.concurrent.Semaphore(4);

    /**
     * 等待空闲执行槽位的最大秒数
     */
    private static final int SLOT_WAIT_SECONDS = 30;

    /**
     * 超时后清理容器的命令超时(秒)
     */
    private static final int CLEANUP_TIMEOUT_SECONDS = 10;

    /**
     * 沙箱执行配置
     */
    public record SandboxConfig(String code, String image,
                                Integer timeoutSeconds, String memory, String cpus) {
    }

    /**
     * 在隔离容器中执行 Python 代码
     *
     * @return 执行输出(含超时/失败说明,末尾附退出码)
     */
    public String execute(SandboxConfig config) {
        if (StringUtils.isBlank(config.code())) {
            return "参数 code 不能为空:请传入要执行的 Python 代码";
        }
        if (!acquireSlot()) {
            return "沙箱执行繁忙(当前排队超过 " + SLOT_WAIT_SECONDS + " 秒),请稍后重试";
        }
        Path workdir = null;
        try {
            workdir = Files.createTempDirectory("forge-sandbox-");
            Files.writeString(workdir.resolve("main.py"), config.code(), StandardCharsets.UTF_8);
            return runContainer(workdir, config);
        } catch (IOException e) {
            log.warn("[Sandbox] 创建工作目录失败: {}", e.getMessage());
            return "沙箱准备工作目录失败: " + e.getMessage();
        } finally {
            cleanup(workdir);
            SLOTS.release();
        }
    }

    /**
     * 申请执行槽位
     */
    private boolean acquireSlot() {
        try {
            return SLOTS.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 运行沙箱容器并收集输出
     */
    private String runContainer(Path workdir, SandboxConfig config) {
        // 显式指定容器名:超时强杀 CLI 进程后仍可通过 docker rm -f 回收容器
        String containerName = "forge-sandbox-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> base = List.of(
            "run", "--rm",
            "--name", containerName,
            "--network", "none",
            "--memory", StringUtils.defaultIfBlank(config.memory(), "128m"),
            "--cpus", StringUtils.defaultIfBlank(config.cpus(), "0.5"),
            "--pids-limit", PIDS_LIMIT,
            "--tmpfs", "/tmp:rw,size=16m",
            "-v", workdir.toAbsolutePath() + ":/sandbox",
            "-w", "/sandbox",
            StringUtils.defaultIfBlank(config.image(), "python:3.11-slim"),
            "python3", "main.py");
        int timeout = config.timeoutSeconds() == null ? 30 : Math.max(1, config.timeoutSeconds());
        log.info("[Sandbox] 开始执行(container={}, timeout={}s, memory={}, cpus={})",
            containerName, timeout, config.memory(), config.cpus());
        List<String> prefix = List.of("docker");
        RunOutcome outcome = dockerRun(prefix, base, timeout, containerName);
        if (outcome.exit != 0 && outcome.output.contains("permission denied")
            && outcome.output.contains("docker.sock")) {
            // 宿主机用户不在 docker 组时兜底走 sudo
            prefix = List.of("sudo", "-n", "docker");
            outcome = dockerRun(prefix, base, timeout, containerName);
        }
        StringBuilder sb = new StringBuilder();
        String result = truncate(outcome.output);
        sb.append(result.isBlank() ? "(无输出)" : result);
        if (outcome.exit != 0) {
            sb.append("\n[退出码: ").append(outcome.exit).append(']');
        }
        return sb.toString();
    }

    /**
     * 单次容器执行结果
     */
    private record RunOutcome(int exit, String output) {
    }

    private RunOutcome dockerRun(List<String> prefix, List<String> base, int timeout, String containerName) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>(prefix);
            command.addAll(base);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            // 必须与 waitFor 并发读取输出:输出超过管道缓冲区(约64KB)时,
            // 先 waitFor 再读会互相等待造成死锁
            StringBuilder sink = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean overflow = new java.util.concurrent.atomic.AtomicBoolean(false);
            Process started = process;
            Thread reader = new Thread(() -> drain(started.getInputStream(), sink, overflow),
                "forge-sandbox-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                destroyTree(process);
                // --rm 只在容器正常退出时生效,需显式强制删除仍存活的容器
                removeContainerQuietly(prefix, containerName);
                reader.join(1000);
                return new RunOutcome(-1, "沙箱执行超时(" + timeout + " 秒),进程已终止;请优化代码后重试");
            }
            reader.join(2000);
            String output = sink.toString();
            if (overflow.get()) {
                output = output + "\n...(输出过长已截断)";
            }
            return new RunOutcome(process.exitValue(), output);
        } catch (IOException e) {
            log.warn("[Sandbox] Docker 执行失败: {}", e.getMessage());
            return new RunOutcome(-1, "沙箱 Docker 执行失败:" + e.getMessage()
                + "(请确认宿主机已安装 Docker 且沙箱镜像已拉取)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断路径同样回收进程与容器,避免线程中断频繁时容器堆积
            if (process != null) {
                destroyTree(process);
                removeContainerQuietly(prefix, containerName);
            }
            return new RunOutcome(-1, "沙箱执行被中断");
        }
    }

    /**
     * 持续读取子进程输出:超出上限后仅读取丢弃,保证管道不被写满
     */
    private void drain(java.io.InputStream in, StringBuilder sink, java.util.concurrent.atomic.AtomicBoolean overflow) {
        try (java.io.Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int total = 0;
            int n;
            while ((n = reader.read(buf)) > 0) {
                if (total >= MAX_READ_CHARS) {
                    overflow.set(true);
                    continue;
                }
                int len = Math.min(n, MAX_READ_CHARS - total);
                sink.append(buf, 0, len);
                total += len;
            }
        } catch (IOException ignore) {
            // 子进程被强杀时读流会中断,忽略
        }
    }

    /**
     * 强杀进程及其子进程
     */
    private void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * 强制删除超时容器(失败仅记录日志)
     */
    private void removeContainerQuietly(List<String> prefix, String containerName) {
        try {
            List<String> command = new ArrayList<>(prefix);
            command.addAll(List.of("rm", "-f", containerName));
            Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroyTree(process);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Sandbox] 清理容器 {} 失败: {}", containerName, e.getMessage());
        }
    }

    private String truncate(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= MAX_OUTPUT ? output : output.substring(0, MAX_OUTPUT) + "\n...(输出过长已截断)";
    }

    private void cleanup(Path workdir) {
        if (workdir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workdir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // ignore
                }
            });
        } catch (IOException e) {
            log.warn("[Sandbox] 清理临时目录失败: {}", e.getMessage());
        }
    }
}
