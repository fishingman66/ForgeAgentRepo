package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.service.governance.ReleaseGateService;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3 门禁接线:canPublish 基础检查沿用原口径,指标项委托 ReleaseGateService.evaluate;
 * 任何异常 fail-closed 返回 false(与 D8 同向)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsGovernanceFacadeImplTest {

    @Mock
    private AgentInfoMapper agentInfoMapper;

    @Mock
    private ReleaseGateService releaseGateService;

    @InjectMocks
    private CsGovernanceFacadeImpl facade;

    private AgentInfo agent(String status, Long modelId, String prompt) {
        AgentInfo agent = new AgentInfo();
        agent.setId(9L);
        agent.setStatus(status);
        agent.setModelId(modelId);
        agent.setSystemPrompt(prompt);
        agent.setWorkspaceId(1L);
        agent.setPublishedVersion(3);
        return agent;
    }

    private ReleaseGateService.GateResult gate(boolean passed) {
        return new ReleaseGateService.GateResult(passed, false, true,
            passed ? "全部检查通过" : "评测得分 40.0 低于门槛 60.0", java.util.Map.of());
    }

    @Test
    void nullAgentIdFailsClosed() {
        assertFalse(facade.canPublish(null));
        verify(agentInfoMapper, never()).selectById(any());
    }

    @Test
    void missingAgentFailsClosed() {
        when(agentInfoMapper.selectById(9L)).thenReturn(null);
        assertFalse(facade.canPublish(9L));
        verify(releaseGateService, never()).evaluate(any());
    }

    @Test
    void disabledAgentRejectedBeforeGate() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("2", 5L, "提示词"));
        assertFalse(facade.canPublish(9L));
        verify(releaseGateService, never()).evaluate(any());
    }

    @Test
    void missingModelOrPromptRejectedBeforeGate() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", null, "提示词"));
        assertFalse(facade.canPublish(9L));
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", 5L, "  "));
        assertFalse(facade.canPublish(9L));
        verify(releaseGateService, never()).evaluate(any());
    }

    @Test
    void gatePassedAllowsPublish() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", 5L, "提示词"));
        when(releaseGateService.evaluate(any())).thenReturn(gate(true));
        assertTrue(facade.canPublish(9L));
    }

    @Test
    void gateBlockedRejectsPublish() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", 5L, "提示词"));
        when(releaseGateService.evaluate(any())).thenReturn(gate(false));
        assertFalse(facade.canPublish(9L));
    }

    @Test
    void gateFailureFailsClosed() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", 5L, "提示词"));
        when(releaseGateService.evaluate(any())).thenThrow(new RuntimeException("db down"));
        assertFalse(facade.canPublish(9L));
    }

    @Test
    void publishedVersionMappedFromEntity() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", 5L, "提示词"));
        assertEquals(3L, facade.currentPublishedVersion(9L));
        when(agentInfoMapper.selectById(9L)).thenReturn(agent("1", 5L, "提示词"));
        assertNull(facade.currentPublishedVersion(null));
        AgentInfo noVersion = agent("1", 5L, "提示词");
        noVersion.setPublishedVersion(null);
        when(agentInfoMapper.selectById(9L)).thenReturn(noVersion);
        assertNull(facade.currentPublishedVersion(9L));
    }
}
