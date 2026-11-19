package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsTraceExtender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentRun;
import com.forgeagent.agent.mapper.AgentRunMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

/** Trace 客服维度回填(C4):独立短事务,失败只告警不阻断对话。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsTraceExtenderImpl implements CsTraceExtender {

    private final AgentRunMapper runMapper;

    @Override
    public void attachCustomerService(String runId, String channelCode, Long visitorId, Long ticketId) {
        if (runId == null) {
            return;
        }
        try {
            runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getRunId, runId)
                .set(AgentRun::getChannelCode, channelCode)
                .set(AgentRun::getVisitorId, visitorId)
                .set(AgentRun::getCsTicketId, ticketId));
        } catch (Exception e) {
            log.warn("[CS] Trace 客服维度回填失败 runId={}: {}", runId, e.getMessage());
        }
    }
}
