package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_eval_run —— 评测域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_eval_run")
public class AgentEvalRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 评测集ID */
    private Long datasetId;

    /** AgentID */
    private Long agentId;

    /** 工作空间ID */
    private Long workspaceId;

    /** running/success/failed */
    private String status;

    /** 用例总数 */
    private Integer totalCases;

    /** 已完成数 */
    private Integer finishedCases;

    /** 平均分 */
    private java.math.BigDecimal avgScore;

    /** 工具成功率 */
    private java.math.BigDecimal toolSuccessRate;

    /** 拒答率 */
    private java.math.BigDecimal refusalRate;

    /** P95延迟ms */
    private Long p95LatencyMs;

    /** 总token */
    private Integer totalTokens;

    /** 是否启用Judge */
    private String judgeEnabled;

    /** 评测时config_hash */
    private String configHash;

    /** 错误信息 */
    private String errorMessage;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

    /** 完成时间 */
    private java.util.Date finishTime;

}