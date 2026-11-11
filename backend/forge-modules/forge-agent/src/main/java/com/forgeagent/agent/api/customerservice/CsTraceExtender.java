package com.forgeagent.agent.api.customerservice;

/**
 * Trace 客服维度扩展契约(S0)。
 *
 * <p>客服专属字段(channel_code / visitor_id / cs_ticket_id)由客服模块在 run 创建后回填,
 * 复用 agent_run 与 RunTraceService 的独立短事务,写失败不阻断对话。</p>
 */
public interface CsTraceExtender {

    void attachCustomerService(String runId, String channelCode, Long visitorId, Long ticketId);
}
