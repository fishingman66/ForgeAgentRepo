package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_cs_audit_log —— 客服合规审计实体(C5,第 3 批迁移)。
 */
@Data
@TableName("agent_cs_audit_log")
public class AgentCsAuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private String channelCode;

    private String eventType;

    /** VISITOR/SEAT/SYSTEM */
    private String actorType;

    private String actorId;

    private String detail;

    /** 结果码(SUCCESS/ERROR/DENIED/INTERRUPTED/QUOTA_BLOCKED) */
    private String resultCode;

    /** 工具执行耗时(毫秒) */
    private Integer durationMs;

    private java.util.Date createTime;
}
