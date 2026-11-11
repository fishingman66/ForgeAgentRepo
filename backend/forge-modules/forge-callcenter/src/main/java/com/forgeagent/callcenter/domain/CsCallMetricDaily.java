package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** 通话日指标(V2,callcenter 自有,不扩 agent_cs_metric_daily)。 */
@Data
@TableName("agent_cc_metric_daily")
public class CsCallMetricDaily {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private java.util.Date statDate;
    private Long workspaceId;
    private Integer callCount;
    private Integer answeredCount;
    private BigDecimal answerRate;
    private Integer avgDurationSec;
    private BigDecimal botResolveRate;
    private BigDecimal recordingCoverage;
    private Date createTime;
    private Date updateTime;
}
