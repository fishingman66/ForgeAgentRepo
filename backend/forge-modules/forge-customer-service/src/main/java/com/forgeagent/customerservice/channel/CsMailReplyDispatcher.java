package com.forgeagent.customerservice.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsVisitor;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * B5:坐席人工回复 → 邮件渠道回信钩子。
 *
 * <p>与 AI 自动回复({@link MailIngestService#pollChannel})分开:那条链路拿着原始邮件,
 * 可用 RFC Message-ID 做 In-Reply-To;人工回复只有工单与访客邮箱,没有原始邮件,
 * 故回信用占位 RawMail(from=收件人),线程头留空——这是"最小版"的诚实边界,
 * 精确线程归并需给会话存原始 Message-ID(另立项)。</p>
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
            MailTransport.RawMail original = new MailTransport.RawMail(null, to, subject, content,
                System.currentTimeMillis());
            mailTransport.send(cfg, original, content);
        } catch (Exception e) {
            // 邮件失败不影响坐席回复主流程与 SSE
            log.warn("[Mail] 工单 {} 人工回复邮件发送失败: {}", ticket.getId(), e.getMessage());
        }
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
