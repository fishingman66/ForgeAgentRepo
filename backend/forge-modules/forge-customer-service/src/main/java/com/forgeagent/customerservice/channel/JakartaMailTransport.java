package com.forgeagent.customerservice.channel;

import com.forgeagent.common.core.exception.ServiceException;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * P3 / B5:基于 jakarta.mail 的 IMAP 收信 + SMTP 回信实现。
 *
 * <p>刻意不复用 {@code forge-common-mail}:那套走全局 {@code mail.*} 单账号配置,
 * 而邮件渠道是<b>渠道级</b>配置(不同空间不同邮箱),必须自带 IMAP/SMTP 凭据。</p>
 *
 * <p>只读未读邮件并按时间正序处理:先回旧邮件,避免同一线程内回复顺序颠倒。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class JakartaMailTransport implements MailTransport {

    /** 单封邮件正文超过该长度截断,避免超长邮件撑爆消息体(客服域上限 4000 字符) */
    private static final int MAX_BODY_CHARS = 4000;

    @Override
    public List<RawMail> fetch(EmailChannelConfig cfg, int max) {
        String protocol = cfg.imapSsl() ? "imaps" : "imap";
        Store store = null;
        Folder folder = null;
        try {
            Session session = Session.getInstance(imapProperties(cfg));
            store = session.getStore(protocol);
            store.connect(cfg.imapHost(), cfg.imapPort(), cfg.imapUser(), cfg.imapPassword());
            folder = store.getFolder(cfg.imapFolder());
            folder.open(Folder.READ_WRITE);

            Message[] messages = folder.getMessages();
            List<RawMail> result = new ArrayList<>();
            // 从后往前取最近的未读邮件,凑够 max 再按时间正序返回
            for (int i = messages.length - 1; i >= 0 && result.size() < max; i--) {
                Message message = messages[i];
                if (message.isSet(Flags.Flag.SEEN)) {
                    continue;
                }
                try {
                    result.add(toRawMail(message));
                    message.setFlag(Flags.Flag.SEEN, true);
                } catch (Exception e) {
                    // 单封解析失败不能让整批中断,否则一封怪邮件会永久卡住邮箱
                    log.warn("[Mail] 邮件解析失败,已跳过: {}", e.getMessage());
                    message.setFlag(Flags.Flag.SEEN, true);
                }
            }
            java.util.Collections.reverse(result);
            return result;
        } catch (Exception e) {
            throw new ServiceException("邮件拉取失败:" + e.getMessage());
        } finally {
            closeQuietly(folder, store);
        }
    }

    @Override
    public void send(EmailChannelConfig cfg, RawMail original, String reply) {
        try {
            Session session = Session.getInstance(smtpProperties(cfg));
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(cfg.fromAddress()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(original.from()));
            String subject = original.subject() == null ? "" : original.subject();
            message.setSubject(subject.toLowerCase().startsWith("re:") ? subject : "Re: " + subject, "UTF-8");
            message.setText(reply, "UTF-8");
            if (original.messageId() != null && !original.messageId().isBlank()) {
                message.setHeader("In-Reply-To", original.messageId());
                message.setHeader("References", original.messageId());
            }
            Transport.send(message);
        } catch (Exception e) {
            throw new ServiceException("邮件回信失败:" + e.getMessage());
        }
    }

    private Properties imapProperties(EmailChannelConfig cfg) {
        Properties props = new Properties();
        props.put("mail.store.protocol", cfg.imapSsl() ? "imaps" : "imap");
        props.put("mail.imap.host", cfg.imapHost());
        props.put("mail.imap.port", String.valueOf(cfg.imapPort()));
        props.put("mail.imap.ssl.enable", String.valueOf(cfg.imapSsl()));
        props.put("mail.imap.connectiontimeout", "10000");
        props.put("mail.imap.timeout", "20000");
        return props;
    }

    private Properties smtpProperties(EmailChannelConfig cfg) {
        Properties props = new Properties();
        props.put("mail.smtp.host", cfg.smtpHost());
        props.put("mail.smtp.port", String.valueOf(cfg.smtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", String.valueOf(cfg.smtpSsl()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        return props;
    }

    private RawMail toRawMail(Message message) throws Exception {
        String messageId = firstHeader(message, "Message-ID");
        Address[] from = message.getFrom();
        String fromText = from != null && from.length > 0 ? from[0].toString() : "";
        String subject = message.getSubject();
        String text = truncate(extractText(message));
        long receivedAt = message.getReceivedDate() == null
            ? System.currentTimeMillis() : message.getReceivedDate().getTime();
        return new RawMail(messageId, fromText, subject, text, receivedAt);
    }

    private String firstHeader(Message message, String name) {
        try {
            String[] values = message.getHeader(name);
            return values == null || values.length == 0 ? null : values[0];
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取正文:优先 text/plain,退而取 text/html 去标签;multipart 递归。
     */
    private String extractText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : String.valueOf(content);
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content == null ? "" : htmlToText(String.valueOf(content));
        }
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            StringBuilder plain = new StringBuilder();
            StringBuilder html = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (child.isMimeType("text/plain")) {
                    plain.append(extractText(child));
                } else if (child.isMimeType("text/html")) {
                    html.append(extractText(child));
                } else if (child.isMimeType("multipart/*")) {
                    String nested = extractText(child);
                    if (!nested.isBlank()) {
                        plain.append(nested);
                    }
                }
            }
            return !plain.isEmpty() ? plain.toString() : html.toString();
        }
        return "";
    }

    private String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replaceAll("[ \\t]+", " ").trim();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_BODY_CHARS ? trimmed.substring(0, MAX_BODY_CHARS) : trimmed;
    }

    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception e) {
            log.debug("[Mail] 关闭文件夹失败: {}", e.getMessage());
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.debug("[Mail] 关闭存储失败: {}", e.getMessage());
        }
    }
}
