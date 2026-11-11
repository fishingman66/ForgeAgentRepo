package com.forgeagent.customerservice.event;

/**
 * 会话隐私擦除完成事件(C4/V2)。
 *
 * <p>由 {@code CsComplianceService.eraseConversation} 在级联清理后发布;
 * 语音域(callcenter)监听本事件级联清理通话记录 / 事件轨迹 / 转写分片与录音对象,
 * 避免 customerservice 反向依赖 callcenter。</p>
 */
public record CsConversationErasedEvent(Long conversationId, Long workspaceId, String channelCode) {
}
