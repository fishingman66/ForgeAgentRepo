package com.forgeagent.agent.service.governance;

import com.forgeagent.agentcore.domain.AgentInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.forgeagent.agent.mapper.AgentEvalBaselineMapper;
import com.forgeagent.agent.mapper.AgentEvalRunMapper;
import com.forgeagent.agent.mapper.AgentRunMapper;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.agent.mapper.ReleaseGateExecutionMapper;
import com.forgeagent.agent.mapper.ReleaseGatePolicyMapper;
import com.forgeagent.agent.mapper.ReleaseGateWaiverMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 A:evaluate 与 preview 的持久化差异——只读评估不写 release_gate_execution,
 * 检查口径完全一致;无样本时仅基础检查即通过(存量空间行为不变的兜底)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ReleaseGateServiceEvaluateTest {

    @Mock private ReleaseGateExecutionMapper executionMapper;
    @Mock private ReleaseGateWaiverMapper waiverMapper;
    @Mock private ReleaseGatePolicyMapper policyMapper;
    @Mock private AgentEvalRunMapper evalRunMapper;
    @Mock private AgentEvalBaselineMapper baselineMapper;
    @Mock private AgentRunMapper agentRunMapper;
    @Mock private AgentSkillMapper skillMapper;

    @InjectMocks
    private ReleaseGateService service;

    private AgentInfo agent() {
        AgentInfo agent = new AgentInfo();
        agent.setId(9L);
        agent.setVersion(2);
        agent.setWorkspaceId(1L);
        agent.setModelId(5L);
        agent.setSystemPrompt("提示词");
        return agent;
    }

    private void stubEmptySamples() {
        lenient().when(policyMapper.selectApplicable(any())).thenReturn(List.of());
        lenient().when(evalRunMapper.latestSuccess(any())).thenReturn(List.of());
        lenient().when(baselineMapper.latestMetrics(any())).thenReturn(List.of());
        lenient().when(agentRunMapper.recent7dMetrics(any())).thenReturn(List.of());
        lenient().when(skillMapper.disabledSkillNames(any())).thenReturn(List.of());
        lenient().when(waiverMapper.countApprovedActive(any())).thenReturn(0);
    }

    @Test
    void evaluateDoesNotPersist() {
        stubEmptySamples();
        ReleaseGateService.GateResult result = service.evaluate(agent());
        assertTrue(result.passed());
        assertFalse(result.sampleAvailable());
        assertTrue(result.checks().containsKey("note"));
        verify(executionMapper, never()).insert(any(com.forgeagent.agent.domain.ReleaseGateExecution.class));
    }

    @Test
    void previewPersistsExactlyOnce() {
        stubEmptySamples();
        ReleaseGateService.GateResult result = service.preview(agent());
        assertTrue(result.passed());
        verify(executionMapper, times(1)).insert(any(com.forgeagent.agent.domain.ReleaseGateExecution.class));
    }

    @Test
    void evaluateAndPreviewShareSameChecks() {
        stubEmptySamples();
        ReleaseGateService.GateResult evaluated = service.evaluate(agent());
        ReleaseGateService.GateResult previewed = service.preview(agent());
        assertEquals(evaluated.checks(), previewed.checks());
        assertEquals(evaluated.reason(), previewed.reason());
    }

    @Test
    void skillBindingReadyIsReportedEvenWithoutSamples() {
        stubEmptySamples();
        ReleaseGateService.GateResult result = service.evaluate(agent());

        assertEquals(Boolean.TRUE, result.checks().get("skillBindingReady"));
        assertEquals(java.util.List.of(), result.checks().get("disabledSkills"));
    }

    @Test
    void disabledInstalledSkillBlocksRelease() {
        stubEmptySamples();
        lenient().when(skillMapper.disabledSkillNames(any()))
            .thenReturn(List.of(java.util.Map.of("name", "退换货处理")));

        ReleaseGateService.GateResult result = service.evaluate(agent());

        assertFalse(result.passed());
        assertTrue(result.reason().contains("已安装技能已停用"));
        assertEquals(List.of("退换货处理"), result.checks().get("disabledSkills"));
    }
}
