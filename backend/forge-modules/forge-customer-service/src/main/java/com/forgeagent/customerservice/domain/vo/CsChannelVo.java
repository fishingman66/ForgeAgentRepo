package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsChannel;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 接入渠道视图对象(不含密钥)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsChannel.class)
public class CsChannelVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String channelCode;
    private String channelType;
    private String knowledgeIds;
    private String corsAllowlist;
    private String sessionMode;
    private String fallbackReply;
    private String handoffPolicy;
    private String locale;

    /** B5:企微 CorpID */
    private String corpId;

    /** B5:是否已启用企微加密模式(EncodingAESKey 不回显,只回布尔) */
    private Boolean wecomEncrypted;

    /** B5:是否已配置邮件参数(配置含密码,只回布尔) */
    private Boolean channelConfigured;

    /** A2:挂件主题色 */
    private String widgetTheme;

    /** A2:挂件形态 */
    private String widgetMode;

    /** A2:开场主动消息 */
    private String widgetWelcome;

    /** A2:挂件扩展配置 JSON */
    private String widgetConfig;

    /** D2:挂件默认语言 */
    private String widgetLang;
    private String status;
    private String fallbackReplyEn;
    private String handoffKeywords;
    private String handoffRules;
    private String identityBinding;
    private Long workspaceId;
    private Long agentId;
    private Long ownerUserId;
    private Long apiKeyId;
    private Integer rateLimit;
    private Date createTime;
    private Date updateTime;
}
