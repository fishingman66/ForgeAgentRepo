package com.forgeagent.callcenter.common;

/**
 * 客服统一事件信封(C0),与传输协议解耦:SSE / WebSocket 共用同一份结构。
 *
 * <p>12 个兼容常量委托 {@link CsEventTypes},保证文本 / 语音两端只有一个取值来源;
 * 调用点 {@code CsEventEnvelope.XXX} 零改动。扩展方向:追加信封字段走 record 组件
 * 顺序追加(序列化兼容),traceId 预留跨服务链路追踪,V1 语音事件已按本信封下发弹屏。</p>
 */
public record CsEventEnvelope(String type, long sequence, Long conversationId, Long ticketId,
                              Object payload, String traceId, long ts) {

    public static final String CHAT = CsEventTypes.CHAT;
    public static final String CHAT_ACCEPTED = CsEventTypes.CHAT_ACCEPTED;
    public static final String CHAT_CHUNK = CsEventTypes.CHAT_CHUNK;
    public static final String CHAT_DONE = CsEventTypes.CHAT_DONE;
    public static final String TICKET_EVENT = CsEventTypes.TICKET_EVENT;
    public static final String TICKET_NEW = CsEventTypes.TICKET_NEW;
    public static final String QUEUE_POSITION = CsEventTypes.QUEUE_POSITION;
    public static final String HANDOFF = CsEventTypes.HANDOFF;
    public static final String AGENT_TYPING = CsEventTypes.AGENT_TYPING;
    public static final String CSAT_INVITE = CsEventTypes.CSAT_INVITE;
    public static final String SYSTEM = CsEventTypes.SYSTEM;
    public static final String ERROR = CsEventTypes.ERROR;

    public static CsEventEnvelope of(String type, Long conversationId, Long ticketId, Object payload) {
        return new CsEventEnvelope(type, 0L, conversationId, ticketId, payload, null, System.currentTimeMillis());
    }

    public CsEventEnvelope withSequence(long seq) {
        return new CsEventEnvelope(type, seq, conversationId, ticketId, payload, traceId, ts);
    }
}
