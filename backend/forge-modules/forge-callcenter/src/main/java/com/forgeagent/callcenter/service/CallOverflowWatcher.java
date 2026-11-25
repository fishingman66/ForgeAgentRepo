package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallEvent;
import com.forgeagent.callcenter.domain.CsOverflowConfig;
import com.forgeagent.callcenter.mapper.CsCallEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 溢出编排(P0-3):在转人工事件后采集指标 → 评估 → 执行 → 事件轨迹留痕。
 *
 * <p>默认关闭且不改变既有行为;任何数据/执行异常都只记日志,不向回调链抛。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallOverflowWatcher {

    private final OverflowEvaluator evaluator;
    private final OverflowMetricsSource metricsSource;
    private final CsCallEventMapper eventMapper;

    /** 转人工事件后调用;返回是否实际触发了溢出动作(供排障)。 */
    public boolean onTransfer(CsCall call) {
        if (call == null || call.getId() == null || !evaluator.enabled()) {
            return false;
        }
        List<CsOverflowConfig> configs = evaluator.configs(call.getWorkspaceId());
        if (configs.isEmpty()) {
            return false;
        }
        OverflowEvaluator.Metrics metrics = new OverflowEvaluator.Metrics(
            metricsSource.queueSize(call.getWorkspaceId()),
            metricsSource.maxWaitSeconds(call.getWorkspaceId()),
            metricsSource.abandonRatePercent(call.getWorkspaceId()));
        OverflowEvaluator.Decision decision = evaluator.evaluate(configs, metrics);
        if (!decision.triggered()) {
            return false;
        }
        OverflowEvaluator.Outcome outcome = evaluator.execute(call, decision);
        record(call, decision, outcome);
        return outcome.sent();
    }

    /** 落事件轨迹:event_seq 用当前毫秒,与真实事件不冲突;重复调用同毫秒由唯一键兜底。 */
    private void record(CsCall call, OverflowEvaluator.Decision d, OverflowEvaluator.Outcome outcome) {
        try {
            CsCallEvent e = new CsCallEvent();
            e.setCallId(call.getId());
            e.setEventSeq(System.currentTimeMillis());
            e.setEventType("call.overflow");
            e.setFromStatus(call.getStatus());
            e.setToStatus(call.getStatus());
            e.setActorType("SYSTEM");
            String payload = d.triggerType() + "=" + d.observed() + "/" + d.threshold()
                + ",action=" + d.action()
                + (d.actionArg() == null ? "" : ",arg=" + d.actionArg())
                + (outcome.downgradeReason() == null ? "" : ",downgrade=" + outcome.downgradeReason());
            e.setPayload(payload.length() > 2000 ? payload.substring(0, 2000) : payload);
            e.setCreateTime(new Date());
            eventMapper.insert(e);
        } catch (Exception ex) {
            log.warn("[CC] 溢出事件留痕失败 callId={}: {}", call.getCallId(), ex.getMessage());
        }
    }
}
