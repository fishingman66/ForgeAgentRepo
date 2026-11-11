package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallStatus;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import org.springframework.stereotype.Component;

/**
 * 呼叫状态机(V1,方案 v2 第七章 8 态)。
 *
 * <p>规则与 V0 契约一致:事件只把状态推向更高 rank,迟到 CDR 与乱序事件不回退;
 * seat_state 仅 readyState=TALKING 时推进(READY/PAUSE 是坐席侧状态,不推进通话);
 * record.ready 与 cdr 不改变状态。HOLD/WRAP_UP 由 V2 话后链路使用。</p>
 */
@Component
@ConditionalOnCallcenterEnabled
public class CallStateMachine {

    /** 归一化事件 -> 目标状态;null 表示不推进。 */
    public String targetOf(CsVoiceEvent event) {
        return switch (event.type()) {
            case "call.ring" -> CsCallStatus.RINGING.name();
            case "call.answer" -> CsCallStatus.ANSWERED.name();
            case "call.asr_result", "call.bot_utterance", "call.dtmf" -> CsCallStatus.BOT_SERVING.name();
            case "call.transfer" -> CsCallStatus.WAITING_AGENT.name();
            case "call.seat_state" -> "TALKING".equalsIgnoreCase(event.str("readyState"))
                ? CsCallStatus.AGENT_SERVING.name() : null;
            case "call.hangup" -> CsCallStatus.ENDED.name();
            default -> null;
        };
    }

    /** 单调推进;返回推进后的状态(可能保持不变)。 */
    public String advance(String current, String target) {
        return CsCallStatus.advance(current, target);
    }
}
