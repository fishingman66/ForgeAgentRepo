package com.forgeagent.agent.service.run;

import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.common.json.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 带事件缓冲的 SSE 发射器(P0 / A1)。
 *
 * <p>与旧实现的本质区别:客户端断连不再中断上游生成。事件先写入 Redis 缓冲,
 * 再尝试写 socket;socket 失败后标记为 detached,后续只写缓冲,生成继续到
 * done/error 并正常落库。客户端带 {@code Last-Event-ID} 重连后由重放端点补齐。</p>
 *
 * <p>每个流首帧固定为 {@code run_id} 事件,前端据此持有断点游标;
 * meta 事件额外注入 runId,调试与重新生成路径同样可续传。</p>
 *
 * <p>体检 P1-3:方法级 {@code synchronized} 在持锁期间做 Redis 网络调用,
 * JDK 21 虚拟线程会 Pin 住载体线程(Redis 抖动时可拖垮全站请求线程池),
 * 改用可重入锁——虚拟线程对其阻塞可正常挂起,事件顺序性语义不变。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
public class RunStreamEmitter implements StreamEmitter {

    private final PrintWriter writer;
    private final RunEventBuffer buffer;
    private final RunCancelRegistry cancelRegistry;
    /** 事件顺序性由该锁保证;虚拟线程下不会 Pin 载体线程 */
    private final ReentrantLock emitLock = new ReentrantLock();
    private String runId;
    private boolean runIdSent;
    private boolean terminated;
    private volatile boolean detached;

    public RunStreamEmitter(PrintWriter writer, RunEventBuffer buffer, RunCancelRegistry cancelRegistry) {
        this.writer = writer;
        this.buffer = buffer;
        this.cancelRegistry = cancelRegistry;
    }

    @Override
    public void bindRun(String id, Long sessionId) {
        emitLock.lock();
        try {
            this.runId = id;
            cancelRegistry.register(id, sessionId);
        } finally {
            emitLock.unlock();
        }
    }

    @Override
    public void send(String event, Object data) {
        emitLock.lock();
        try {
            ensureRunIdFrame();
            Object payload = data;
            if ("meta".equals(event) && runId != null && data instanceof Map<?, ?> raw) {
                Map<String, Object> enriched = new LinkedHashMap<>();
                raw.forEach((k, v) -> enriched.put(String.valueOf(k), v));
                enriched.put("runId", runId);
                payload = enriched;
            }
            emit(event, payload);
            if ("done".equals(event) || "error".equals(event)) {
                markTerminal(event);
            }
        } finally {
            emitLock.unlock();
        }
    }

    @Override
    public void finish() {
        emitLock.lock();
        try {
            // 异常收尾或断连收尾时,保证重放连接能判定终态并自动关闭
            if (!terminated) {
                markTerminal("done");
            }
            if (runId != null) {
                cancelRegistry.unregister(runId);
            }
        } finally {
            emitLock.unlock();
        }
    }

    public boolean isDetached() {
        return detached;
    }

    public String getRunId() {
        return runId;
    }

    /** 首帧下发 runId,使所有流式入口(含重新生成/调试)都具备续传能力 */
    private void ensureRunIdFrame() {
        if (runId == null || runIdSent) {
            return;
        }
        runIdSent = true;
        emit("run_id", Map.of("runId", runId));
    }

    private void emit(String event, Object data) {
        String payload = data instanceof String s ? s : JsonUtils.toJsonString(data);
        long sequence = buffer.publish(runId, event, payload);
        writeFrame(sequence, event, payload);
    }

    private void writeFrame(long sequence, String event, String payload) {
        if (detached) {
            return;
        }
        try {
            if (sequence > 0) {
                writer.write("id: " + sequence + "\n");
            }
            writer.write("event: " + event + "\n");
            writer.write("data: " + oneLine(payload) + "\n\n");
            writer.flush();
            if (writer.checkError()) {
                detached = true;
                log.info("[RunStreamEmitter] 客户端断连 runId={},后续事件仅写入缓冲,生成继续", runId);
            }
        } catch (Exception e) {
            detached = true;
            log.info("[RunStreamEmitter] SSE 写出失败 runId={},后续事件仅写入缓冲: {}", runId, e.getMessage());
        }
    }

    private void markTerminal(String status) {
        if (terminated || runId == null) {
            return;
        }
        terminated = true;
        buffer.terminal(runId, status);
    }

    /** SSE data 行不能含裸换行,JSON 内以转义序列承载 */
    private static String oneLine(String payload) {
        if (payload == null) {
            return "";
        }
        return payload.replace("\r", "\\r").replace("\n", "\\n");
    }
}
