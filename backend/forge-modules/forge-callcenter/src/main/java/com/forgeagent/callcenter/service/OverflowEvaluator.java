package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsOverflowConfig;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.mapper.CsOverflowConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 溢出评估与执行(P0-3)。
 *
 * <p><b>零回退</b>:{@code overflow-enabled=false}(默认)或无策略行时 evaluate 返回 none,
 * 不下发任何通道命令。三类触发条件的数据源均为现有资产:
 * QUEUE_SIZE(排队条数)、QUEUE_TIMEOUT(排队最久等待秒)、ABANDON_RATE(日指标呼损率%)。</p>
 *
 * <p><b>动作边界</b>:TRANSFER_QUEUE / HANGUP 复用 {@link ChannelControl} 已有方法;
 * TRANSFER_IVR 因当前无"二次下发 IVR 流程"的下行契约,降级为 MARK 留痕(不伪造能力)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverflowEvaluator {

    /** 溢出决策。 */
    public record Decision(boolean triggered, String action, String actionArg,
                           String triggerType, long observed, long threshold) {
        public static Decision none() {
            return new Decision(false, null, null, null, 0, 0);
        }
    }

    /** 触发判定输入(由调用方从现有数据源采集,便于单测与解耦)。 */
    public record Metrics(int queueSize, long maxWaitSeconds, int abandonRatePercent) {
    }

    /** 执行结果(供留痕:downgraded=目标缺失/无通道等降级原因,null 表示正常执行)。 */
    public record Outcome(boolean sent, String downgradeReason) {
        public static Outcome dispatched() {
            return new Outcome(true, null);
        }

        public static Outcome downgraded(String reason) {
            return new Outcome(false, reason);
        }
    }

    private final CsOverflowConfigMapper mapper;
    private final CallcenterProperties properties;
    private final LiveChannelRegistry liveChannels;

    /** 是否启用(默认关闭)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getOverflowEnabled());
    }

    /** 按 workspace 精确取启用策略(无策略返回空列表)。 */
    public List<CsOverflowConfig> configs(Long workspaceId) {
        if (!enabled() || workspaceId == null) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<CsOverflowConfig>()
            .eq(CsOverflowConfig::getWorkspaceId, workspaceId)
            .eq(CsOverflowConfig::getEnabled, "0"));
    }

    /** 纯判定:按 trigger_type 比较 observed 与 threshold,取第一个命中的策略。 */
    public Decision evaluate(List<CsOverflowConfig> configs, Metrics metrics) {
        if (configs == null || configs.isEmpty() || metrics == null) {
            return Decision.none();
        }
        for (CsOverflowConfig c : configs) {
            if (c == null || c.getThreshold() == null || c.getTriggerType() == null) {
                continue;
            }
            long observed = observed(metrics, c.getTriggerType());
            if (observed < 0) {
                continue; // 指标缺失不触发
            }
            if (observed >= c.getThreshold()) {
                String action = normalizeAction(c.getAction());
                return new Decision(true, action, c.getActionArg(), c.getTriggerType(),
                    observed, c.getThreshold());
            }
        }
        return Decision.none();
    }

    /** 执行决策:只对已触发且目标有效的策略下发,异常降级不中断主链路。 */
    public Outcome execute(CsCall call, Decision d) {
        if (!d.triggered() || call == null || call.getCallId() == null) {
            return Outcome.downgraded("NO_DECISION");
        }
        ChannelControl channel = liveChannels.find(call.getCallId());
        try {
            switch (d.action()) {
                case "TRANSFER_QUEUE" -> {
                    if (d.actionArg() == null || d.actionArg().isBlank()) {
                        return Outcome.downgraded("EMPTY_TARGET");
                    }
                    if (channel == null) {
                        return Outcome.downgraded("NO_LIVE_CHANNEL");
                    }
                    channel.transferToQueue(d.actionArg());
                    return Outcome.dispatched();
                }
                case "HANGUP" -> {
                    if (channel == null) {
                        return Outcome.downgraded("NO_LIVE_CHANNEL");
                    }
                    channel.hangup(d.actionArg());
                    return Outcome.dispatched();
                }
                default -> {
                    return Outcome.downgraded("MARK_ONLY");
                }
            }
        } catch (Exception e) {
            log.warn("[CC] 溢出执行失败 callId={} action={}: {}", call.getCallId(), d.action(), e.getMessage());
            return Outcome.downgraded("EXEC_FAILED");
        }
    }

    /** TRANSFER_IVR 降级为 MARK:当前无二次下发 IVR 流程的下行契约。 */
    static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "MARK";
        }
        return switch (action.trim().toUpperCase()) {
            case "TRANSFER_QUEUE", "HANGUP", "MARK" -> action.trim().toUpperCase();
            default -> "MARK";
        };
    }

    /** 指标映射:未知触发类型返回 -1(不触发),缺数据不伪造。 */
    static long observed(Metrics m, String triggerType) {
        if (m == null || triggerType == null) {
            return -1;
        }
        return switch (triggerType.trim().toUpperCase()) {
            case "QUEUE_SIZE" -> m.queueSize();
            case "QUEUE_TIMEOUT" -> m.maxWaitSeconds();
            case "ABANDON_RATE" -> m.abandonRatePercent();
            default -> -1;
        };
    }
}
