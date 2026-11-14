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

    /** C8 扩维:转人工数/率。 */
    private Integer transferCount;
    private BigDecimal transferRate;

    /** C8 扩维:呼损数(振铃未接通)/率。 */
    private Integer abandonCount;
    private BigDecimal abandonRate;

    /** C8 扩维:平均等待时长(振铃→接听,秒)。 */
    private Integer avgWaitSec;

    /** C8 扩维:含负面情绪洞察的通话数/占比(来自 C4 洞察表)。 */
    private Integer negativeCalls;
    private BigDecimal negativeRate;

    /** C8 扩维:当日语音质检均分(0-100)。 */
    private BigDecimal qualityAvgScore;

    private Date createTime;
    private Date updateTime;
}
