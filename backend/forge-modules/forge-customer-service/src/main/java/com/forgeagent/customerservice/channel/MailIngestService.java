package com.forgeagent.customerservice.channel;

import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.service.CsChannelDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * P3 / B5:邮件 → 会话的摄取编排。
 *
 * <p>邮件不新造一条"建会话/建单"链路,而是复用 {@link CsChannelDispatchService#handle}:
 * 与公众号/企微同一条入口,白拿会话复用、回执幂等、转人工与 AI 回复。</p>
 *
 * <p>幂等:<b>Message-ID 哈希后作为 {@code clientMsgId}</b>,由
 * {@code agent_cs_message_receipt} 的 {@code uk_csr_dedup(session_id, client_msg_id)} 兜底。
 * 原始 Message-ID 常超 64 字符(该列长度上限),所以必须定长化。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailIngestService {

    /** external_user_id 与 client_msg_id 的列长上限都比 RFC 报文头小得多 */
    private static final int MAX_EXTERNAL_ID_LEN = 128;

    private final MailTransport mailTransport;
    private final CsChannelDispatchService dispatch;
    private final CsSecretCipher secretCipher;

    /**
     * 拉取并处理一个邮件渠道的全部未读邮件。
     *
     * @return 成功处理的封数
     */
    public int pollChannel(CsChannel channel) {
        if (channel == null) {
            return 0;
        }
        EmailChannelConfig cfg = EmailChannelConfig.parse(decryptConfig(channel.getChannelConfig()));
        List<MailTransport.RawMail> mails = mailTransport.fetch(cfg, cfg.maxPerPoll());
        int handled = 0;
        for (MailTransport.RawMail mail : mails) {
            try {
                String key = messageKey(mail.messageId(), mail.from(), mail.subject());
                String visitor = normalizeAddress(mail.from());
                if (StringUtils.isBlank(visitor)) {
                    log.warn("[Mail] 渠道 {} 的邮件缺少发件人,已跳过", channel.getChannelCode());
                    continue;
                }
                String content = StringUtils.isBlank(mail.text()) ? "(空邮件正文)" : mail.text();
                CsInboundMessage inbound = new CsInboundMessage(key, visitor, content, "email", cfg.imapUser());
                String reply = dispatch.handle(channel, inbound);
                if (cfg.replyEnabled() && StringUtils.isNotBlank(reply)) {
                    mailTransport.send(cfg, mail, reply);
                }
                handled++;
            } catch (Exception e) {
                // 单封失败不影响后续:下一轮轮询仍会重试(邮件此时已被标记已读,但幂等键保证不重复入会话)
                log.warn("[Mail] 渠道 {} 处理邮件失败: {}", channel.getChannelCode(), e.getMessage());
            }
        }
        return handled;
    }

    /**
     * 配置解密:整段 JSON 以 AES-GCM 存储(含 IMAP/SMTP 密码)。
     */
    private String decryptConfig(String stored) {
        if (StringUtils.isBlank(stored)) {
            return stored;
        }
        return secretCipher == null ? stored : secretCipher.decrypt(stored);
    }

    /**
     * 生成幂等键:优先 Message-ID,缺失时退化为 from+subject。
     *
     * <p>输出形如 {@code em:<32 hex>},定长 35 字符,远小于 64 的列上限。</p>
     */
    public static String messageKey(String rawMessageId, String from, String subject) {
        String basis = StringUtils.isNotBlank(rawMessageId)
            ? rawMessageId.trim()
            : (from == null ? "" : from.trim()) + '|' + (subject == null ? "" : subject.trim());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("em:");
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "em:" + Integer.toHexString(basis.hashCode());
        }
    }

    /**
     * 从 {@code "张三 <a@b.com>"} 取纯地址并转小写;超长截断到列上限。
     */
    public static String normalizeAddress(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String value = raw.trim();
        int lt = value.lastIndexOf('<');
        int gt = value.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            value = value.substring(lt + 1, gt).trim();
        }
        value = value.toLowerCase();
        return value.length() > MAX_EXTERNAL_ID_LEN ? value.substring(0, MAX_EXTERNAL_ID_LEN) : value;
    }
}
