package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * release_gate_policy —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("release_gate_policy")
public class ReleaseGatePolicy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 工作空间(NULL=平台默认) */
    private Long workspaceId;

    /** 资源类型 */
    private String resourceType;

    /** 启用 */
    private String enabled;

    /** 最低评测分 */
    private java.math.BigDecimal minEvalScore;

    /** 最大失败率 */
    private java.math.BigDecimal maxFailureRate;

    /** 最大P95延迟 */
    private Long maxP95LatencyMs;

    /** 要求基线 */
    private String requireBaseline;

    /** 最大退化 */
    private java.math.BigDecimal maxMetricRegression;

    /** 要求安全通过 */
    private String requireSecurityPass;

    /** 要求成本预算 */
    private String requireCostBudget;

    /** 创建时间 */
    private java.util.Date createTime;

}