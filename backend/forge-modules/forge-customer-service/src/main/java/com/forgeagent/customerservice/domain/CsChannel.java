package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服渠道端点 agent_cs_channel(C0)。 */
@Data
@TableName("agent_cs_channel")
public class CsChannel {
    @TableId private Long id;
    private String channelCode, channelType, knowledgeIds, corsAllowlist;
    private String sessionMode, fallbackReply, handoffPolicy, locale, status, channelSecret;
    private String fallbackReplyEn;
    /** 转人工关键词(P0):逗号分隔,空则用平台默认。 */
    private String handoffKeywords;
    /** B2:转人工规则链 JSON([{type,enabled,...}]),空用内置默认。 */
    private String handoffRules;
    /** C2:访客身份绑定方式(external_token/phone_claim 等),空=未配置(WRITE/HIGH_RISK 工具 deny)。 */
    private String identityBinding;
    /** A2:挂件主题色(#RRGGBB),空用默认。 */
    private String widgetTheme;
    /** A2:挂件形态 bubble/sidebar。 */
    private String widgetMode;
    /** A2:开场主动消息。 */
    private String widgetWelcome;
    /** A2:挂件扩展配置 JSON(logo/position/size 等)。 */
    private String widgetConfig;
    private Long workspaceId, agentId, ownerUserId, apiKeyId;
    private Integer rateLimit;
    private Date createTime, updateTime;
}
