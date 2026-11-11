package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 语音渠道配置(V1):callcenter 自有,不扩 agent_cs_channel(C 形态改进)。
 * 密钥用 CsSecretCipher 加密存储;为空时回退全局 agent.callcenter.callback-secret。
 */
@Data
@TableName("agent_cc_channel_config")
public class CsCallChannelConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String channelCode;
    private String mediaProvider;
    private String serviceNumber;
    private Long workspaceId;
    private Long agentId;
    private String callbackSecret;
    private String voiceConfigJson;
    private Integer rateLimit;
    private String status;
    private Long delFlag;
    private Date createTime;
    private Date updateTime;
}
