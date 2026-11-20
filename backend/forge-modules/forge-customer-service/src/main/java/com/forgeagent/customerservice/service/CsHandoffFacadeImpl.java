package com.forgeagent.customerservice.service;

import com.forgeagent.agent.api.customerservice.CsHandoffFacade;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工作流转人工契约实现(C1):按会话建工单,复用 {@link CsTicketService#requestHandoff}。
 *
 * <p>只做归属校验与参数收敛,不复制建单逻辑,保证与访客端转人工同一条路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsHandoffFacadeImpl implements CsHandoffFacade {

    private final CsConversationMapper conversationMapper;
    private final CsTicketService ticketService;

    @Override
    public HandoffResult transfer(Long workspaceId, Long conversationId, String reason) {
        if (conversationId == null) {
            throw new IllegalArgumentException("缺少会话 ID,无法转人工");
        }
        CsConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在,无法转人工");
        }
        if (workspaceId != null && conversation.getWorkspaceId() != null
            && !workspaceId.equals(conversation.getWorkspaceId())) {
            // 统一话术:不区分"不存在"与"不属于本空间",避免存在性探测
            throw new IllegalArgumentException("会话不存在,无法转人工");
        }
        String finalReason = reason == null || reason.isBlank() ? "工作流触发转人工" : reason;
        CsTicket ticket = ticketService.requestHandoff(conversation, finalReason);
        log.info("[工作流转人工] conversationId={} ticketId={}", conversationId, ticket.getId());
        return new HandoffResult(ticket.getId(), ticket.getTicketNo(), ticket.getStatus());
    }
}
