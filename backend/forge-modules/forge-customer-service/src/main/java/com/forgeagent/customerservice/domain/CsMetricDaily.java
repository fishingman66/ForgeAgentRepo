package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服日指标 agent_cs_metric_daily(C2)。 */
@Data
@TableName("agent_cs_metric_daily")
public class CsMetricDaily {
    @TableId private Long id;
    private String channelCode;
    private Long workspaceId;
    private Integer botSessions, handoffCount;
    private Long firstResponseP95Ms;
    private Double resolveRate, handoffRate, csatAvg, csatResponseRate, slaPassRate;
    private Date statDate;
}
