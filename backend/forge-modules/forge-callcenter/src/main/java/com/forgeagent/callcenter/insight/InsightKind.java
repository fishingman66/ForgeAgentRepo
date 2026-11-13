package com.forgeagent.callcenter.insight;

/**
 * 洞察类别(C4,方案 docs/07 §三 P0-2)。
 *
 * <p>四类共表 {@code agent_cc_call_insight},用唯一键
 * {@code (call_id, kind, item_key)} 区分实例:</p>
 * <ul>
 *   <li>{@link #SENTIMENT} 通话中按轮次评估,itemKey = {@code turn-<segmentId>},受节流约束;</li>
 *   <li>{@link #INTENT} 挂机后全文归类,itemKey 固定 {@code final},重跑只更新;</li>
 *   <li>{@link #EXTRACT} 挂机后按渠道声明字段抽取,itemKey = 字段名;</li>
 *   <li>{@link #QA_REPORT} 挂机后聚合报告,itemKey 固定 {@code default}。</li>
 * </ul>
 */
public enum InsightKind {

    SENTIMENT,
    INTENT,
    EXTRACT,
    QA_REPORT;

    /** 是否挂机后执行(通话中只跑情绪)。 */
    public boolean postCall() {
        return this != SENTIMENT;
    }
}
