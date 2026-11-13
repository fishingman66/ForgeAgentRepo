package com.forgeagent.callcenter.common;

import java.util.Set;

/**
 * 文本域与语音域事件名的唯一来源(V0.5)。
 *
 * <p>文本 12 个取值与 S0~C4 已交付的 SSE 事件完全一致,不得改动;
 * 语音 9 个为控制面下行事件名,与 AICC 回调归一化事件分层,V1 由 AiccGateway 映射。</p>
 *
 * <p>扩展方向:新增事件 = 此处加常量并加入对应 Set;TEXT_EVENTS / VOICE_EVENTS
 * 被 CsContractConstantsTest 锁定,常量集合变化必须同步更新单测;
 * 外呼(主叫方向)、IVR 节点上报、坐席辅助话术等新事件按同一模式扩展。</p>
 */
public final class CsEventTypes {

    /** 文本域事件(S0~C4)。 */
    public static final String CHAT = "chat";
    public static final String CHAT_ACCEPTED = "chat_accepted";
    public static final String CHAT_CHUNK = "chat_chunk";
    public static final String CHAT_DONE = "chat_done";
    public static final String TICKET_EVENT = "cs_ticket_event";
    public static final String TICKET_NEW = "cs_ticket_new";
    public static final String QUEUE_POSITION = "cs_queue_position";
    public static final String HANDOFF = "cs_handoff";
    public static final String AGENT_TYPING = "cs_agent_typing";
    public static final String CSAT_INVITE = "cs_csat_invite";
    /** 坐席辅助推荐(A1):payload 只增不改,候选 items 上限 3 条。 */
    public static final String SEAT_SUGGEST = "cs_seat_suggest";
    public static final String SYSTEM = "system";
    public static final String ERROR = "error";

    /** 语音域控制面事件(V1~V2)。 */
    public static final String CALL_RING = "call.ring";
    public static final String CALL_ANSWER = "call.answer";
    public static final String CALL_HANGUP = "call.hangup";
    public static final String CALL_DTMF = "call.dtmf";
    public static final String CALL_TRANSCRIPT = "call.transcript";
    public static final String CALL_RECORD_READY = "call.record.ready";
    public static final String CALL_HOLD = "call.hold";
    public static final String CALL_RESUME = "call.resume";
    public static final String CALL_TRANSFER = "call.transfer";
    /** C4 洞察:负面情绪上行坐席弹屏(payload 带 label/score/autoTransfer)。 */
    public static final String CALL_SENTIMENT = "call.sentiment";

    public static final Set<String> TEXT_EVENTS = Set.of(
        CHAT, CHAT_ACCEPTED, CHAT_CHUNK, CHAT_DONE,
        TICKET_EVENT, TICKET_NEW, QUEUE_POSITION, HANDOFF,
        AGENT_TYPING, CSAT_INVITE, SEAT_SUGGEST, SYSTEM, ERROR);

    public static final Set<String> VOICE_EVENTS = Set.of(
        CALL_RING, CALL_ANSWER, CALL_HANGUP, CALL_DTMF, CALL_TRANSCRIPT,
        CALL_RECORD_READY, CALL_HOLD, CALL_RESUME, CALL_TRANSFER, CALL_SENTIMENT);

    public static final Set<String> ALL_EVENTS = Set.of(
        CHAT, CHAT_ACCEPTED, CHAT_CHUNK, CHAT_DONE,
        TICKET_EVENT, TICKET_NEW, QUEUE_POSITION, HANDOFF,
        AGENT_TYPING, CSAT_INVITE, SEAT_SUGGEST, SYSTEM, ERROR,
        CALL_RING, CALL_ANSWER, CALL_HANGUP, CALL_DTMF, CALL_TRANSCRIPT,
        CALL_RECORD_READY, CALL_HOLD, CALL_RESUME, CALL_TRANSFER, CALL_SENTIMENT);

    private CsEventTypes() {
    }
}
