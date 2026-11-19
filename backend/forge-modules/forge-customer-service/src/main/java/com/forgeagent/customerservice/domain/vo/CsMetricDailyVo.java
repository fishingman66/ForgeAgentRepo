package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsMetricDaily;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 运营日指标视图对象(字段对齐实体 botSessions/handoffCount/resolveRate 等)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsMetricDaily.class)
public class CsMetricDailyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long workspaceId;

    private String channelCode;

    /** 统计日 */
    private Date statDate;

    private Integer botSessions;

    private Integer handoffCount;

    /** 首响 P95(毫秒) */
    private Long firstResponseP95Ms;

    /** 一次解决率(0-100 百分数) */
    private Double resolveRate;

    /** 转人工率(0-100) */
    private Double handoffRate;

    /** CSAT 均分 */
    private Double csatAvg;

    /** CSAT 回收率(0-100) */
    private Double csatResponseRate;

    /** SLA 达标率(0-100) */
    private Double slaPassRate;

    /** 重复进线率(0-100) */
    private Double repeatContactRate;

    /** 一次解决含转人工兜底的容纳率(0-100) */
    private Double containmentRate;
}
