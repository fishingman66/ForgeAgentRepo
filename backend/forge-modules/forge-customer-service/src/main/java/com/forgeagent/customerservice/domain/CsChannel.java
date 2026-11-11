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
    private Long workspaceId, agentId, ownerUserId, apiKeyId;
    private Integer rateLimit;
    private Date createTime, updateTime;
}
