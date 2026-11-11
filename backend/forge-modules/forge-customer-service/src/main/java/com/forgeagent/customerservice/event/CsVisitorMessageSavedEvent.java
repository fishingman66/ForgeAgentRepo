package com.forgeagent.customerservice.event;

import java.util.List;

/**
 * 访客消息保存事件(A1,坐席辅助触发源)。
 *
 * <p>由 {@code CsVisitorService.send} 在消息受理完成（CHAT_DONE 推送后）发布,
 * 幂等重放（reused=true）不发布。消费方 {@code SeatAssistService} 异步生成坐席话术推荐,
 * 推荐失败不影响访客主链路。事件对象只携带推荐所需的最小字段。</p>
 */
public record CsVisitorMessageSavedEvent(
    Long conversationId,
    Long ticketId,
    Long workspaceId,
    Long agentId,
    String channelCode,
    String content,
    List<Long> knowledgeIds) {
}
