package com.forgeagent.customerservice.channel;

import java.util.List;

/**
 * P3 / B5:邮件收发传输层。
 *
 * <p>抽成接口是为了让「收信 → 转会话 → 回信」的业务编排可以脱离真实 IMAP/SMTP 单测
 * (CI 里不会有邮箱账号,也不该让单测连外网)。</p>
 *
 * @author ForgeAgent
 */
public interface MailTransport {

    /**
     * 拉取未读邮件(不标记已读,由实现自行决定;重复拉取靠消息幂等键兜底)。
     *
     * @param cfg 渠道配置
     * @param max 单次上限
     */
    List<RawMail> fetch(EmailChannelConfig cfg, int max);

    /**
     * 回信(保持原主题,加 Re: 前缀)。
     */
    void send(EmailChannelConfig cfg, RawMail original, String reply);

    /**
     * 一封原始邮件。
     *
     * @param messageId  RFC Message-ID(可能为空,此时用 from+subject 兜底做幂等键)
     * @param from       发件人(原始头,可能形如 {@code "张三 <a@b.com>"})
     * @param subject    主题
     * @param text       正文纯文本
     * @param receivedAt 收信时间(毫秒)
     */
    record RawMail(String messageId, String from, String subject, String text, long receivedAt) {
    }
}
