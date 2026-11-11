package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 坐席辅助日指标(A5),对应表 {@code agent_cs_assist_metric_daily}。
 *
 * <p>口径(用户 2026-11-10 明确):<b>采纳率 = 采纳条数 / 推荐条数</b>,按推荐条数算,
 * 不按动作次数算——一条推荐上执行了 REPLY 又执行 RESOLVE,仍只算一条采纳。</p>
 */
@Data
@TableName("agent_cs_assist_metric_daily")
public class CsAssistMetricDaily {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    /** 统计日(当天 00:00)。 */
    private Date statDate;
    private Integer suggestionCount;
    private Integer adoptedCount;
    private Integer actionCount;
    private Integer intentCount;
    private Integer recycledCount;
    private Date createTime;
    private Date updateTime;
}
