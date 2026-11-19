package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_run —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_run")
public class AgentRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "runId", type = IdType.ASSIGN_ID)
    private String runId;

    /** 工作空间 */
    private Long workspaceId;

    /** AgentID */
    private Long agentId;

    /** 会话ID */
    private Long sessionId;

    /** 用户ID */
    private Long userId;

    /** 模型标识 */
    private String modelName;

    /** 运行类型 */
    private String runType;

    /** 状态 */
    private String status;

    /** 提示token */
    private Integer promptTokens;

    /** 补全token */
    private Integer completionTokens;

    /** 总token */
    private Integer totalTokens;

    /** 延迟 */
    private Long latencyMs;

    /** 首token延迟 */
    private Long firstTokenLatencyMs;

    /** 错误 */
    private String errorMessage;

    /** 渠道 */
    private String channelCode;

    /** 访客ID */
    private Long visitorId;

    /** 工单ID */
    private Long csTicketId;

    /** 创建时间 */
    private java.util.Date createTime;

    /** 完成时间 */
    private java.util.Date finishTime;

}