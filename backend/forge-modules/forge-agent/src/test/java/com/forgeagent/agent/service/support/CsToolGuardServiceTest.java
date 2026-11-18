package com.forgeagent.agent.service.support;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 C:客服工具护栏——
 * C2 未配置身份绑定的渠道写操作 fail-closed;C3 幂等键展开与重复回放;
 * Redis 不可用降级放行;C5 WRITE 全量审计 / READ 只在失败时留痕。
 * 单测环境无 Redis:额度检查按降级放行路径覆盖。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsToolGuardServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private AgentTool delegate;

    @Mock
    private ToolCallParam param;

    private CsToolGuardService service;

    @BeforeAll
    static void setUpClass() {
    }

    @BeforeAll
    static void explainClassLoading() {
        // 无需预热:护栏不解析 MP lambda
    }

    private com.forgeagent.agent.domain.AgentTool def(String risk, String actionKey, Integer dailyLimit) {
        com.forgeagent.agent.domain.AgentTool def = new com.forgeagent.agent.domain.AgentTool();
        def.setId(1L);
        def.setName("refund_order");
        def.setRiskLevel(risk);
        def.setActionKey(actionKey);
        def.setDailyLimit(dailyLimit);
        return def;
    }

    private CsChatFacade.GuardContext ctx(boolean identityBindingEnabled) {
        return new CsChatFacade.GuardContext(5L, 11L, 1L, "web", identityBindingEnabled);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Mockito 先注入 @Mock 再跑 @BeforeEach,这里才能拿到非 null 的 jdbc
        service = new CsToolGuardService(jdbc);
    }

    private void stubDelegateResult() {
        lenient().when(delegate.getName()).thenReturn("refund_order");
        lenient().when(delegate.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("退款已受理")));
        lenient().when(param.getInput()).thenReturn(Map.of("orderNo", "12345"));
    }

    @Test
    void resolveActionKeyExpandsTemplateAndFallsBack() {
        assertEquals("refund_order:refund:12345",
            CsToolGuardService.resolveActionKey("refund:{orderNo}", Map.of("orderNo", "12345"), "refund_order"));
        // 占位参数缺失:退化为工具级键(宁可多拦不漏拦)
        assertEquals("refund_order",
            CsToolGuardService.resolveActionKey("refund:{orderNo}", Map.of(), "refund_order"));
        assertNull(CsToolGuardService.resolveActionKey(" ", Map.of("orderNo", "1"), "refund_order"));
    }

    @Test
    void writeWithoutIdentityBindingIsDenied() {
        stubDelegateResult();
        AgentTool guarded = service.decorator(ctx(false), Map.of("refund_order", def("WRITE", null, null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertTrue(CsToolGuardService.CsToolGuard.extractSummary(block).contains("身份核验"));
        verify(delegate, never()).callAsync(any());
        // 拦截也要留痕(GUARD_DENY):INSERT 有 6 个占位参数
        verify(jdbc).update(contains("agent_cs_audit_log"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void readToolPassesThroughWithoutAudit() {
        stubDelegateResult();
        AgentTool guarded = service.decorator(ctx(false), Map.of("refund_order", def("READ", null, null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(block));
        verify(jdbc, never()).update(contains("agent_cs_audit_log"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeWithIdentityBindingRunsAndAudits() {
        stubDelegateResult();
        lenient().when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(7L);
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", "refund:{orderNo}", null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(block));
        // 幂等占位 + 收尾 + 审计
        verify(jdbc).update(contains("INSERT INTO agent_cs_action_log"), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc).update(contains("UPDATE agent_cs_action_log SET status=?"), anyString(), any(), any());
        verify(jdbc).update(contains("agent_cs_audit_log"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateActionKeyReplaysInsteadOfExecuting() {
        stubDelegateResult();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
            Map.of("id", 7L, "status", "DONE", "result_summary", "退款已受理")));
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", "refund:{orderNo}", null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertTrue(CsToolGuardService.CsToolGuard.extractSummary(block).contains("已拦截"));
        verify(delegate, never()).callAsync(any());
    }

    @Test
    void failedActionAllowsRetry() {
        stubDelegateResult();
        java.util.Map<String, Object> failedRow = new HashMap<>();
        failedRow.put("id", 7L);
        failedRow.put("status", "FAILED");
        failedRow.put("result_summary", null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(failedRow));
        when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(7L);
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", "refund:{orderNo}", null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(block));
        verify(jdbc).update(contains("SET status='RUNNING'"), any(Object[].class));
    }

    @Test
    void toolsOutsideEntityMapPassThrough() {
        stubDelegateResult();
        AgentTool guarded = service.decorator(ctx(false), Map.of())
            .apply(delegate);

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(guarded.callAsync(param).block()));
        verify(delegate).callAsync(any());
    }

    @Test
    void readOnlyLimitStillApplies() {
        stubDelegateResult();
        // 无 Redis 环境:额度降级放行(不阻断主链路)
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("READ", null, 3)))
            .apply(delegate);

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(guarded.callAsync(param).block()));
        verify(delegate).callAsync(any());
    }
}
