package com.forgeagent.customerservice.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsVisitor;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * B5:坐席人工回复 → 邮件渠道回信钩子。
 *
 * <p>与 AI 自动回复({@link MailIngestService#pollChannel})分开:A-3 后人工回复从
 * {@code agent_cs_message_receipt.raw_message_id} 取回原始 RFC Message-ID 填线程头,
 * 老数据无该值时退化为占位 RawMail(线程头留空),仍按地址回信。</p>
 *
 * <p>本组件所有失败都只记日志、不外抛:坐席回复主流程与 SSE 推送不能因邮件发送失败而回滚/报错,
 * 与 {@code MailIngestService} 的单封失败不中断纪律一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsMailReplyDispatcher {

    private static final String CHANNEL_TYPE_EMAIL = "email";
    private static final String CHANNEL_STATUS_ENABLED = "0";
    /** 人工回复无原始邮件主题,用工单号做主题锚点,避免出现空主题邮件。 */
    private static final String SUBJECT_PREFIX = "Re: [客服工单] ";

    private final CsChannelMapper channelMapper;
    private final CsVisitorMapper visitorMapper;
    private final MailTransport mailTransport;
    private final CsSecretCipher secretCipher;
    private final CsMessageReceiptMapper receiptMapper;

    /**
     * 若工单属于启用的邮件渠道且开启回信,则把坐席回复发到访客邮箱。
     * 非邮件渠道静默返回;任何异常只记日志。
     */
    public void sendReply(CsTicket ticket, String content) {
        if (ticket == null || content == null || content.isBlank()) {
            return;
        }
        try {
            CsChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<CsChannel>()
                .eq(CsChannel::getWorkspaceId, ticket.getWorkspaceId())
                .eq(CsChannel::getChannelCode, ticket.getChannelCode())
                .eq(CsChannel::getChannelType, CHANNEL_TYPE_EMAIL)
                .eq(CsChannel::getStatus, CHANNEL_STATUS_ENABLED)
                .last("limit 1"));
            if (channel == null) {
                return; // 非邮件渠道或渠道停用
            }
            EmailChannelConfig cfg = EmailChannelConfig.parse(secretCipher.decrypt(channel.getChannelConfig()));
            if (!cfg.replyEnabled()) {
                return;
            }
            String to = resolveRecipient(ticket, channel);
            if (to == null) {
                log.warn("[Mail] 工单 {} 邮件回信跳过:访客无可用邮箱", ticket.getId());
                return;
            }
            String subject = SUBJECT_PREFIX + (ticket.getTicketNo() == null ? ticket.getId() : ticket.getTicketNo());
            // A-3:线程头来自本会话最近一条带原始 Message-ID 的回执;老数据为 null 自然退化
            MailTransport.RawMail original = new MailTransport.RawMail(findRawMessageId(ticket), to, subject,
                content, System.currentTimeMillis());
            mailTransport.send(cfg, original, content);
        } catch (Exception e) {
            // 邮件失败不影响坐席回复主流程与 SSE
            log.warn("[Mail] 工单 {} 人工回复邮件发送失败: {}", ticket.getId(), e.getMessage());
        }
    }

    /**
     * A-3:取会话最近一条带原始 Message-ID 的回执。
     * ticket.sessionId 在 agentSessionId 未分配时由 CsTicketService 直接置 null,
     * 而回执 dedupKey 此时退化为 -conversationId,两种口径都要覆盖。
     */
    private String findRawMessageId(CsTicket ticket) {
        Long sid = ticket.getSessionId();
        long sessionId = sid != null && sid > 0 ? sid
            : (ticket.getConversationId() == null ? 0L : -ticket.getConversationId());
        if (sessionId == 0L) {
            return null;
        }
        CsMessageReceipt receipt = receiptMapper.selectOne(new LambdaQueryWrapper<CsMessageReceipt>()
            .eq(CsMessageReceipt::getSessionId, sessionId)
            .isNotNull(CsMessageReceipt::getRawMessageId)
            .orderByDesc(CsMessageReceipt::getId)
            .last("limit 1"));
        return receipt == null ? null : receipt.getRawMessageId();
    }

    /** 收件人优先取访客外部标识(收信时 normalizeAddress 已是纯邮箱)。 */
    private String resolveRecipient(CsTicket ticket, CsChannel channel) {
        if (ticket.getVisitorId() == null) {
            return null;
        }
        CsVisitor visitor = visitorMapper.selectById(ticket.getVisitorId());
        if (visitor == null || visitor.getExternalUserId() == null || visitor.getExternalUserId().isBlank()) {
            return null;
        }
        return visitor.getExternalUserId().trim();
    }
}
