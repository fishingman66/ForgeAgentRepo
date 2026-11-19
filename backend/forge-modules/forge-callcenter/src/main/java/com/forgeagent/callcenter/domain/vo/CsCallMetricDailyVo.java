package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 通话日指标视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallMetricDaily.class)
public class CsCallMetricDailyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * statDate
     */
    private Date statDate;

    /**
     * workspaceId
     */
    private Long workspaceId;

    /**
     * callCount
     */
    private Integer callCount;

    /**
     * answeredCount
     */
    private Integer answeredCount;

    /**
     * answerRate
     */
    private BigDecimal answerRate;

    /**
     * avgDurationSec
     */
    private Integer avgDurationSec;

    /**
     * botResolveRate
     */
    private BigDecimal botResolveRate;

    /**
     * recordingCoverage
     */
    private BigDecimal recordingCoverage;

    /**
     * transferCount
     */
    private Integer transferCount;

    /**
     * transferRate
     */
    private BigDecimal transferRate;

    /**
     * abandonCount
     */
    private Integer abandonCount;

    /**
     * abandonRate
     */
    private BigDecimal abandonRate;

    /**
     * avgWaitSec
     */
    private Integer avgWaitSec;

    /**
     * negativeCalls
     */
    private Integer negativeCalls;

    /**
     * negativeRate
     */
    private BigDecimal negativeRate;

    /**
     * qualityAvgScore
     */
    private BigDecimal qualityAvgScore;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
