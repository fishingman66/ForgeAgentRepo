package com.forgeagent.callcenter.route;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.insight.ChannelVoiceSettings;
import com.forgeagent.callcenter.insight.InsightPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无 IVR「机器人直接应答」模式的分流器(C5,方案 docs/07 §三 P0-3 的第二个消费点)。
 *
 * <p>与 IVR {@code intention} 节点的区别:那里是<b>流程内</b>的同步分支(节点拿意图码跳节点),
 * 这里是<b>无流程</b>的自由对话——每个主叫终稿到达就分流一次,动作直接下发。</p>
 *
 * <p>动作执行口径(不假装):</p>
 * <ul>
 *   <li>{@code TRANSFER}:有活通道 → 立即 {@code transferToQueue};没有媒体面
 *       (AICC 回调链路)→ 只留痕 {@code PENDING_NO_CHANNEL};</li>
 *   <li>{@code ANSWER}:需要媒体面才能播报,留痕 {@code ANSWER_PENDING},真实应答随 C1-real/C7 接入;</li>
 *   <li>{@code MARK}:只记录,不动通话。</li>
 * </ul>
 *
 * <p>分流与动作在回调线程同步完成(转接要赶在通话还在进行时),只有洞察落库异步。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class DirectAnswerRouter {

    /** 决策落库的实例键前缀。 */
    public static final String ITEM_PREFIX = "direct-";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HybridIntentionClassifier classifier;
    private final ChannelVoiceSettings channelSettings;
    private final LiveChannelRegistry liveChannels;
    private final InsightPipeline insightPipeline;

    /** 主叫终稿到达时调用;渠道未开启直接应答则什么都不做。 */
    public void onSegment(CsCall call, CsCallSegment segment) {
        if (call == null || call.getId() == null || segment == null || segment.getId() == null
            || segment.getText() == null || segment.getText().isBlank()) {
            return;
        }
        if (!"caller".equalsIgnoreCase(segment.getSpeaker()) || !"1".equals(segment.getIsFinal())) {
            return;
        }
        ChannelVoiceSettings.Settings settings = channelSettings.of(call.getChannelCode());
        if (!settings.directAnswer()) {
            return;
        }
        HybridIntentionClassifier.Decision decision;
        try {
            decision = classifier.route(call.getWorkspaceId(), segment.getText(), List.of());
        } catch (Exception e) {
            log.warn("[CC][Route] 直接应答分流失败 callId={}: {}", call.getCallId(), e.getMessage());
            return;
        }
        if (decision == null) {
            return;
        }
        String outcome = apply(call, decision);
        record(call, segment, decision, outcome);
    }

    /** 执行动作;返回结果码用于留痕与排障。 */
    private String apply(CsCall call, HybridIntentionClassifier.Decision decision) {
        String action = decision.action() == null ? "MARK" : decision.action().toUpperCase();
        switch (action) {
            case "TRANSFER" -> {
                ChannelControl channel = liveChannels.find(call.getCallId());
                if (channel == null) {
                    log.warn("[CC][Route] 转接动作无在线通道,仅留痕 callId={} queue={}",
                        call.getCallId(), decision.actionArg());
                    return "PENDING_NO_CHANNEL";
                }
                try {
                    channel.transferToQueue(decision.actionArg());
                    log.info("[CC][Route] 直接应答分流转人工 callId={} queue={} intent={}",
                        call.getCallId(), decision.actionArg(), decision.intentCode());
                    return "FIRED";
                } catch (Exception e) {
                    log.warn("[CC][Route] 转接下发失败 callId={}: {}", call.getCallId(), e.getMessage());
                    return "FAILED";
                }
            }
            case "ANSWER" -> {
                // 播报需要媒体面(TTS 管道),回调链路只登记决策,不谎称已应答
                log.info("[CC][Route] 直接应答待媒体面 callId={} intent={} arg={}",
                    call.getCallId(), decision.intentCode(), decision.actionArg());
                return "ANSWER_PENDING";
            }
            default -> {
                return "MARKED";
            }
        }
    }

    private void record(CsCall call, CsCallSegment segment,
                        HybridIntentionClassifier.Decision decision, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engine", decision.engine());
        payload.put("rule", decision.ruleCode());
        payload.put("action", decision.action());
        payload.put("actionArg", decision.actionArg());
        payload.put("outcome", outcome);
        payload.put("confidence", decision.confidence());
        payload.put("textLen", segment.getText().length());
        insightPipeline.onRouteDecision(call, ITEM_PREFIX + segment.getId(), segment.getId(),
            decision.intentCode(), toJson(payload));
    }

    private static String toJson(Map<String, ?> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
