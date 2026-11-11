package com.forgeagent.customerservice.event;

/**
 * 业务动作失败事件(P2 收尾):由失败方发布,死信服务监听落库。
 *
 * <p>用事件而不是直接注入,是为了避免「摘要服务 → 死信服务 → 摘要服务」的循环依赖:
 * 死信需要重投摘要,摘要失败又要记死信,两者只能通过事件解耦。</p>
 */
public record CsBusinessFailureEvent(
    Long workspaceId,
    String channelCode,
    String eventType,
    Long conversationId,
    Long ticketId,
    String error) {
}
