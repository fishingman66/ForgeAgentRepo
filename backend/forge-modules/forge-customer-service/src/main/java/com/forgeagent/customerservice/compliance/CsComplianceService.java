package com.forgeagent.customerservice.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsAuditLog;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsCsat;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.domain.CsQualityCheck;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import com.forgeagent.customerservice.domain.CsVisitor;
import com.forgeagent.customerservice.mapper.CsAuditLogMapper;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsCsatMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.customerservice.mapper.CsQualityCheckMapper;
import com.forgeagent.customerservice.mapper.CsTicketEventMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 客服合规(C4):审计留痕、隐私擦除、留存到期清理。
 *
 * <p>删除全部按明确 id / 时间条件下推到 SQL,小批量执行,重复跑幂等;
 * 定时任务每日 03:00 触发,多实例重复执行也只是重复删除,不产生副作用。</p>
 *
 * <p>扩展方向:擦除范围扩展(V2 起通过 {@code CsConversationErasedEvent} 由
 * callcenter 级联清理语音数据,本类不反向依赖语音域);审计事件类型按
 * ERASE / RECORDING_* / CALL_CDR_RECONCILE 持续追加;合规报表导出在 listAudit 上叠加。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsComplianceService {

    private final CsAuditLogMapper auditMapper;
    private final CsConversationMapper conversationMapper;
    private final CsMessageReceiptMapper receiptMapper;
    private final CsCsatMapper csatMapper;
    private final CsQualityCheckMapper qualityMapper;
    private final CsTicketEventMapper ticketEventMapper;
    private final CsTicketMapper ticketMapper;
    private final CsVisitorMapper visitorMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final CsModuleProperties properties;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public void record(String eventType, String actorType, String actorId,
                       Long workspaceId, String channelCode, String detail) {
        CsAuditLog entry = new CsAuditLog();
        entry.setEventType(eventType);
        entry.setActorType(actorType);
        entry.setActorId(actorId);
        entry.setWorkspaceId(workspaceId);
        entry.setChannelCode(channelCode);
        entry.setDetail(detail == null ? null : detail.substring(0, Math.min(1000, detail.length())));
        entry.setCreateTime(new Date());
        auditMapper.insert(entry);
    }

    public List<CsAuditLog> listAudit(Long workspaceId) {
        return auditMapper.selectList(new LambdaQueryWrapper<CsAuditLog>()
            .eq(CsAuditLog::getWorkspaceId, workspaceId)
            .orderByDesc(CsAuditLog::getId).last("limit 200"));
    }

    /** 隐私擦除:级联清理该会话的全部客服域与消息数据,并留审计。 */
    public int eraseConversation(Long conversationId, String actorType, String actorId) {
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return 0;
        }
        Long sessionId = conv.getAgentSessionId();
        List<CsTicket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getConversationId, conversationId));
        for (CsTicket ticket : tickets) {
            ticketEventMapper.delete(new LambdaQueryWrapper<CsTicketEvent>()
                .eq(CsTicketEvent::getTicketId, ticket.getId()));
            qualityMapper.delete(new LambdaQueryWrapper<CsQualityCheck>()
                .eq(CsQualityCheck::getTicketId, ticket.getId()));
        }
        ticketMapper.delete(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getConversationId, conversationId));
        csatMapper.delete(new LambdaQueryWrapper<CsCsat>()
            .eq(CsCsat::getConversationId, conversationId));
        receiptMapper.delete(new LambdaQueryWrapper<CsMessageReceipt>()
            .eq(CsMessageReceipt::getSessionId, sessionId == null ? -1L : sessionId));
        if (sessionId != null) {
            messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId));
            sessionMapper.deleteById(sessionId);
        }
        conversationMapper.deleteById(conversationId);
        CsVisitor visitor = visitorMapper.selectById(conv.getVisitorId());
        if (visitor != null) {
            visitor.setErasedAt(new Date());
            visitorMapper.updateById(visitor);
        }
        // V2:语音域(callcenter)监听本事件级联清理通话记录 / 转写与录音对象,依赖方向不变
        eventPublisher.publishEvent(new com.forgeagent.customerservice.event.CsConversationErasedEvent(
            conversationId, conv.getWorkspaceId(), conv.getChannelCode()));
        record("ERASE", actorType, actorId, conv.getWorkspaceId(), conv.getChannelCode(),
            "conversationId=" + conversationId);
        return 1;
    }

    /** 留存到期清理:返回本次删除的会话数。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public int purgeExpired() {
        int days = properties.getRetentionDays() == null ? 180 : properties.getRetentionDays();
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -Math.max(1, days));
        Date deadline = calendar.getTime();
        List<CsConversation> expired = conversationMapper.selectList(
            new LambdaQueryWrapper<CsConversation>()
                .lt(CsConversation::getCreateTime, deadline).last("limit 100"));
        int removed = 0;
        for (CsConversation conv : expired) {
            removed += eraseConversation(conv.getId(), "SYSTEM", "retention");
        }
        if (removed > 0) {
            log.info("[CS] 留存清理完成 removed={} retentionDays={}", removed, days);
            record("RETENTION_PURGE", "SYSTEM", "retention", null, null,
                "removed=" + removed + ", days=" + days);
        }
        return removed;
    }
}
