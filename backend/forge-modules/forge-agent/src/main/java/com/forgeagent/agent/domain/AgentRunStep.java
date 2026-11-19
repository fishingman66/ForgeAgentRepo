package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_run_step —— 运行步骤明细实体(JdbcTemplate 治理迁移,第 3 批)。
 */
@Data
@TableName("agent_run_step")
public class AgentRunStep implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 运行 ID */
    private String runId;

    /** 步骤序号 */
    private Integer stepNo;

    /** ROUTER/CONTEXT/RETRIEVAL/MEMORY/SEMANTIC/BUDGET/LLM/TEAM/WORKFLOW/ERROR */
    private String stepType;

    /** 步骤名称 */
    private String stepName;

    /** success/failed/skipped */
    private String status;

    /** 步骤耗时 */
    private Long latencyMs;

    /** 该步骤 Token 用量 */
    private Integer tokenUsage;

    /** 阶段载荷 JSON(截断后) */
    private String payload;

    private java.util.Date createTime;
}
