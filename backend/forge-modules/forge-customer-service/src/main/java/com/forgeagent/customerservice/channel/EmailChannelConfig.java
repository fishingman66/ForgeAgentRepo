package com.forgeagent.customerservice.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;

/**
 * P3 / B5:邮件渠道配置(存 {@code agent_cs_channel.channel_config},整体 AES-GCM 加密)。
 *
 * <p>因为配置里含 IMAP/SMTP 密码,<b>整段 JSON 加密存储</b>而不是只加密密码字段——
 * 少一次"忘了某个字段"的机会,且与 {@code channel_secret} 同级别保护。</p>
 *
 * <p>示例:</p>
 * <pre>
 * {"imapHost":"imap.example.com","imapPort":993,"imapUser":"support@example.com",
 *  "imapPassword":"******","imapFolder":"INBOX","imapSsl":true,
 *  "smtpHost":"smtp.example.com","smtpPort":465,"smtpUser":"support@example.com",
 *  "smtpPassword":"******","smtpSsl":true,"fromAddress":"support@example.com",
 *  "replyEnabled":true,"maxPerPoll":20}
 * </pre>
 *
 * @author ForgeAgent
 */
public record EmailChannelConfig(String imapHost, int imapPort, String imapUser, String imapPassword,
                                 String imapFolder, boolean imapSsl,
                                 String smtpHost, int smtpPort, String smtpUser, String smtpPassword,
                                 boolean smtpSsl, String fromAddress,
                                 boolean replyEnabled, int maxPerPoll) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次轮询上限:防止积压邮箱把一次调度拖成长任务 */
    public static final int HARD_MAX_PER_POLL = 100;

    public static EmailChannelConfig parse(String configJson) {
        if (StringUtils.isBlank(configJson)) {
            throw new ServiceException("邮件渠道缺少配置 channelConfig");
        }
        try {
            JsonNode root = MAPPER.readTree(configJson);
            EmailChannelConfig cfg = new EmailChannelConfig(
                text(root, "imapHost"),
                root.path("imapPort").asInt(993),
                text(root, "imapUser"),
                text(root, "imapPassword"),
                root.path("imapFolder").asText("INBOX"),
                root.path("imapSsl").asBoolean(true),
                text(root, "smtpHost"),
                root.path("smtpPort").asInt(465),
                text(root, "smtpUser"),
                text(root, "smtpPassword"),
                root.path("smtpSsl").asBoolean(true),
                text(root, "fromAddress"),
                root.path("replyEnabled").asBoolean(true),
                clamp(root.path("maxPerPoll").asInt(20), 1, HARD_MAX_PER_POLL));
            cfg.validate();
            return cfg;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("邮件渠道配置不是合法 JSON");
        }
    }

    /**
     * 校验必填项。收信与回信可分别启用:不填 SMTP 时 replyEnabled 必须为 false,
     * 避免"配置看起来完整、回信静默丢失"。
     */
    public void validate() {
        if (StringUtils.isBlank(imapHost) || StringUtils.isBlank(imapUser) || StringUtils.isBlank(imapPassword)) {
            throw new ServiceException("邮件渠道缺少 IMAP 主机/账号/密码");
        }
        if (replyEnabled) {
            if (StringUtils.isBlank(smtpHost) || StringUtils.isBlank(smtpUser) || StringUtils.isBlank(smtpPassword)) {
                throw new ServiceException("已开启自动回信,但缺少 SMTP 主机/账号/密码");
            }
            if (StringUtils.isBlank(fromAddress)) {
                throw new ServiceException("已开启自动回信,但缺少 fromAddress");
            }
        }
    }

    /** 日志/审计用:隐去账号与密码 */
    public String describe() {
        return "imap=" + imapHost + ":" + imapPort + "/" + imapFolder
            + ", reply=" + replyEnabled + (replyEnabled ? (", smtp=" + smtpHost + ":" + smtpPort) : "");
    }

    private static String text(JsonNode root, String field) {
        String value = root.path(field).asText("");
        return value.isBlank() ? null : value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
