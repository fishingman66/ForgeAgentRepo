package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 会话满意度 agent_cs_csat(C2)。 */
@Data
@TableName("agent_cs_csat")
public class CsCsat {
    @TableId private Long id;
    private String channelCode, comment, tags;
    private Long conversationId, workspaceId;
    private Integer score;
    private Date invitedAt, submittedAt;
}
