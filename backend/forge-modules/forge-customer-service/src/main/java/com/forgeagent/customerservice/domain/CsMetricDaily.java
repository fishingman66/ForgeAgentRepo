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
    /** B3:重复进线率(%):当日会话中同访客 24h 内已有过进线的占比。 */
    private Double repeatContactRate;
    /** B3:AI 自动解决率(%):未产生转人工工单即终结的会话占比。 */
    private Double containmentRate;
    private Date statDate;
}
