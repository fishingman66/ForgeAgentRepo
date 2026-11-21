package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A4 服务漏斗真实口径:三段全部来自 agent_cs_conversation 同一实体,避免"会话数来自会话表、
 * 转人工数来自工单表"的跨实体混算。定义与 CsMetricService 的 containmentRate 同源:
 * 未建单会话(ticket_id IS NULL)= AI 自动解决,已建单会话 = 转人工。
 *
 * <p>纯聚合 VO,无对应持久化实体,故不挂 @AutoMapper(同 CsAssistOverviewVo 先例)。</p>
 */
@Data
public class CsFunnelVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 区间会话总数 */
    private long sessions;

    /** 未建单会话数(=AI 自动解决;与 containmentRate 同源) */
    private long contained;

    /** 已建单会话数(=转人工) */
    private long handoff;
}
