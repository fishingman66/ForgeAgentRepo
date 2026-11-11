package com.forgeagent.customerservice.event;

/**
 * 转人工已建单事件(P0):用于异步生成会话交接摘要。
 *
 * <p>与 A1 的 {@link CsVisitorMessageSavedEvent} 同一模式——建单主链路只发布事件,
 * 摘要生成失败不影响转人工本身。</p>
 */
public record CsHandoffRequestedEvent(
    Long workspaceId,
    Long conversationId,
    Long ticketId) {
}
