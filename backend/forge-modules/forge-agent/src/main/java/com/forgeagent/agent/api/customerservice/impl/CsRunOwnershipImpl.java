package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsRunOwnership;
import com.forgeagent.agent.service.run.RunTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.mapper.AgentRunMapper;
import org.springframework.stereotype.Service;


/** 运行归属校验(C4):控制台按登录用户,客服按访客 + 渠道。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsRunOwnershipImpl implements CsRunOwnership {

    private final RunTraceService runTrace;
    private final AgentRunMapper runMapper;

    @Override
    public boolean isOwnedByUser(String runId, Long userId) {
        if (runId == null || userId == null) {
            return false;
        }
        Long owner = runTrace.ownerOf(runId);
        return owner != null && owner.equals(userId);
    }

    @Override
    public boolean isOwnedByVisitor(String runId, Long visitorId, String channelCode) {
        if (runId == null || visitorId == null || channelCode == null) {
            return false;
        }
        try {
            com.forgeagent.agent.domain.vo.AgentRunVo run = runMapper.selectVisitorChannel(runId);
            if (run == null) {
                return false;
            }
            return visitorId.equals(run.getVisitorId())
                && channelCode.equals(run.getChannelCode());
        } catch (Exception e) {
            log.warn("[CS] 访客归属校验失败 runId={}: {}", runId, e.getMessage());
            return false;
        }
    }

}
