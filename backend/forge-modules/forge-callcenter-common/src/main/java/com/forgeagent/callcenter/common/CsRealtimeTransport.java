package com.forgeagent.callcenter.common;

/**
 * 实时通道抽象(C0)。业务层只发 {@link CsEventEnvelope},
 * 文本期走 SseRealtimeTransport(留在 customerservice),语音媒体面走 CsVoiceGateway,业务代码零改动。
 *
 * <p>扩展方向:V0-real 接真实 AICC 后的媒体面(WebSocket)、坐席辅助话术推送、
 * 外呼进度通知都实现本接口即可,业务层投递方式不变;CsPeer 预留
 * channelCode 用于多渠道弹屏路由。</p>
 */
public interface CsRealtimeTransport {

    String name();

    void open(CsPeer peer);

    void push(CsEventEnvelope envelope);

    /** SSE 实现不支持二进制,显式失败而非静默丢帧;媒体面走 CsVoiceGateway。 */
    default void pushBinary(Long conversationId, byte[] data) {
        throw new UnsupportedOperationException("transport " + name() + " 不支持二进制帧(语音期需切 websocket)");
    }

    void close(Long conversationId, String reason);

    record CsPeer(Long conversationId, Long visitorId, String channelCode) {
    }
}
