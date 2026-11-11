package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsTraceExtender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Trace 客服维度回填(C4):独立短事务,失败只告警不阻断对话。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsTraceExtenderImpl implements CsTraceExtender {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void attachCustomerService(String runId, String channelCode, Long visitorId, Long ticketId) {
        if (runId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                "UPDATE agent_run SET channel_code = ?, visitor_id = ?, cs_ticket_id = ? WHERE run_id = ?",
                channelCode, visitorId, ticketId, runId);
        } catch (Exception e) {
            log.warn("[CS] Trace 客服维度回填失败 runId={}: {}", runId, e.getMessage());
        }
    }
}
