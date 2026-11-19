package com.forgeagent.agent.service.governance;

import com.forgeagent.agentcore.domain.AgentInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private JdbcTemplate jdbc;

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
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    }

    @Test
    void evaluateDoesNotPersist() {
        stubEmptySamples();
        ReleaseGateService.GateResult result = service.evaluate(agent());
        assertTrue(result.passed());
        assertFalse(result.sampleAvailable());
        assertTrue(result.checks().containsKey("note"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void previewPersistsExactlyOnce() {
        stubEmptySamples();
        ReleaseGateService.GateResult result = service.preview(agent());
        assertTrue(result.passed());
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
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
        // 更具体的 stub 放在后面,优先于 anyString 的全局空返回
        when(jdbc.queryForList(contains("JOIN agent_skill s"), any(Object[].class)))
            .thenReturn(List.of(java.util.Map.of("name", "退换货处理")));

        ReleaseGateService.GateResult result = service.evaluate(agent());

        assertFalse(result.passed());
        assertTrue(result.reason().contains("已安装技能已停用"));
        assertEquals(List.of("退换货处理"), result.checks().get("disabledSkills"));
    }
}
