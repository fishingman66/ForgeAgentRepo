package com.forgeagent.agent.service.run;

import com.forgeagent.agent.service.runner.AgentScopeRunner;
import com.forgeagent.common.redis.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Run 级跨实例取消注册表(P0 / A3)。
 *
 * <p>三级取消:① 中断请求写入 Redis 令牌(跨实例可见)与会话→run 映射;
 * ② 流式 delta 回调主动检查令牌,当帧即可触发内核中断;
 * ③ 500ms 观察器兜底扫描本实例活跃 run,覆盖无 delta 的等待态(工具执行、审批、长推理)。
 * 命中后统一交给 AgentScope 内核中断标志,由 handleInterrupt 正常收尾落库。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class RunCancelRegistry {

    private static final String CANCEL_PREFIX = "forge:run:cancel:";
    private static final String SESSION_RUN_PREFIX = "forge:session:run:";
    private static final Duration TTL = Duration.ofHours(6);

    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final AgentScopeRunner runner;

    private record Active(Long sessionId, AtomicBoolean fired) {
    }

    public RunCancelRegistry(AgentScopeRunner runner) {
        this.runner = runner;
    }

    /** 绑定本实例正在执行的 run */
    public void register(String runId, Long sessionId) {
        if (runId == null || sessionId == null) {
            return;
        }
        active.put(runId, new Active(sessionId, new AtomicBoolean(false)));
        try {
            RBucket<String> binding = RedisUtils.getClient().getBucket(SESSION_RUN_PREFIX + sessionId);
            binding.set(runId);
            binding.expire(TTL);
        } catch (Exception e) {
            log.warn("[RunCancel] 会话运行映射写入失败 runId={}: {}", runId, e.getMessage());
        }
    }

    /** 运行结束,释放注册与令牌 */
    public void unregister(String runId) {
        Active removed = active.remove(runId);
        try {
            RedisUtils.getClient().getBucket(CANCEL_PREFIX + runId).delete();
        } catch (Exception ignore) {
            // Redis 不可用不影响主链路
        }
        if (removed != null) {
            try {
                RedisUtils.getClient().getBucket(SESSION_RUN_PREFIX + removed.sessionId()).delete();
            } catch (Exception ignore) {
                // 同上
            }
        }
    }

    /**
     * 按会话发出取消指令,返回对应的 runId。
     * 令牌写 Redis 后任意实例都能观察到;同时立即在本实例触发一次。
     */
    public String requestCancel(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            RBucket<String> binding = RedisUtils.getClient().getBucket(SESSION_RUN_PREFIX + sessionId);
            String runId = binding.get();
            if (runId == null) {
                return null;
            }
            RBucket<String> token = RedisUtils.getClient().getBucket(CANCEL_PREFIX + runId);
            token.set("1");
            token.expire(TTL);
            fire(runId);
            return runId;
        } catch (Exception e) {
            log.warn("[RunCancel] 取消指令写入失败 sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 逐 delta 检查点:命中即触发本实例内核中断 */
    public void check(String runId) {
        if (runId != null && active.containsKey(runId) && isCancelled(runId)) {
            fire(runId);
        }
    }

    /** 观察器兜底:覆盖长时间没有增量片段的运行态 */
    @Scheduled(fixedDelay = 500)
    public void watch() {
        if (active.isEmpty()) {
            return;
        }
        for (String runId : active.keySet()) {
            Active current = active.get(runId);
            if (current != null && !current.fired().get() && isCancelled(runId)) {
                fire(runId);
            }
        }
    }

    private void fire(String runId) {
        Active target = active.get(runId);
        if (target == null || !target.fired().compareAndSet(false, true)) {
            return;
        }
        if (!isCancelled(runId)) {
            target.fired().set(false);
            return;
        }
        log.info("[RunCancel] 运行 {} 收到取消指令,向会话 {} 的内核 Agent 广播中断", runId, target.sessionId());
        runner.interrupt(target.sessionId());
    }

    private boolean isCancelled(String runId) {
        try {
            return RedisUtils.getClient().<String>getBucket(CANCEL_PREFIX + runId).get() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
