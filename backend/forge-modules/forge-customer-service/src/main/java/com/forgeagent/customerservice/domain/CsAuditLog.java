package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服合规审计 agent_cs_audit_log(C4)。 */
@Data
@TableName("agent_cs_audit_log")
public class CsAuditLog {
    @TableId private Long id;
    private String channelCode, eventType, actorType, actorId, detail;
    private Long workspaceId;
    private Date createTime;
}
