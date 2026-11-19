package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_cs_action_log —— 客服动作幂等日志实体(C3,第 3 批迁移)。
 */
@Data
@TableName("agent_cs_action_log")
public class AgentCsActionLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 客服会话ID */
    private Long conversationId;

    private Long visitorId;

    private Long workspaceId;

    private String channelCode;

    private String toolName;

    /** 幂等业务键(模板按入参展开) */
    private String actionKey;

    private String riskLevel;

    /** RUNNING/DONE/FAILED/BLOCKED */
    private String status;

    /** 执行结果摘要(供重复请求回放) */
    private String resultSummary;

    private java.util.Date createTime;
}
