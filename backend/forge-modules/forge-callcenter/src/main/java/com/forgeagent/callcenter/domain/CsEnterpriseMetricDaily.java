package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 企业维度日指标(P1-3):按企业树聚合 workspace 的通话指标。
 *
 * <p>唯一键 (enterprise_id, stat_date);企业 id 来自 CsEnterprise(workspace 的上层),
 * 聚合口径 = 本企业 + 子树所有 workspace 的 CsCallMetricDaily 之和。</p>
 */
@Data
@TableName("agent_cc_enterprise_metric_daily")
public class CsEnterpriseMetricDaily implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long enterpriseId;

    private Date statDate;

    /** 聚合的 workspace 数量(可观测性)。 */
    private Integer workspaceCount;

    private Integer callCount;

    private Integer answeredCount;

    private BigDecimal answerRate;

    private Integer transferCount;

    private Integer abandonCount;

    private BigDecimal abandonRate;

    private Integer avgDurationSec;

    private Integer negativeCalls;

    private BigDecimal qualityAvgScore;

    private Date createTime;

    private Date updateTime;
}
