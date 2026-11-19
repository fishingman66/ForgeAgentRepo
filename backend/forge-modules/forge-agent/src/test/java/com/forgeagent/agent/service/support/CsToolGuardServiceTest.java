package com.forgeagent.agent.service.support;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.domain.AgentCsActionLog;
import com.forgeagent.agent.domain.AgentCsAuditLog;
import com.forgeagent.agent.mapper.AgentCsActionLogMapper;
import com.forgeagent.agent.mapper.AgentCsAuditLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 C:客服工具护栏——
 * C2 未配置身份绑定的渠道写操作 fail-closed;C3 幂等键展开与重复回放;
 * Redis 不可用降级放行;C5 WRITE 全量审计 / READ 只在失败时留痕。
 * 单测环境无 Redis:额度检查按降级放行路径覆盖。
 * JdbcTemplate 治理(第 3 批):mock 换为 AgentCsActionLogMapper / AgentCsAuditLogMapper。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsToolGuardServiceTest {

    @Mock
    private AgentCsActionLogMapper actionLogMapper;

    @Mock
    private AgentCsAuditLogMapper auditLogMapper;

    @Mock
    private AgentTool delegate;

    @Mock
    private ToolCallParam param;

    private CsToolGuardService service;

    @BeforeAll
    static void warmUpLambdaCache() {
        // 纯 Mockito 下 LambdaWrapper 构造即解析列名,需预热实体元数据
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentCsActionLog.class);
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

    @BeforeEach
    void setUp() {
        service = new CsToolGuardService(actionLogMapper, auditLogMapper);
        // MP insert 不回填 id,桩里模拟回填(与呼叫中心同口径)
        lenient().doAnswer(inv -> {
            AgentCsActionLog entity = inv.getArgument(0);
            entity.setId(7L);
            return 1;
        }).when(actionLogMapper).insert(any(AgentCsActionLog.class));
    }

    private void stubDelegateResult() {
        lenient().when(delegate.getName()).thenReturn("refund_order");
        lenient().when(delegate.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("退款已受理")));
        lenient().when(param.getInput()).thenReturn(Map.of("orderNo", "12345"));
    }

    /** 幂等占位行:selectOne 命中时返回,缺省返回 null(无记录)。 */
    private void stubExistingLog(String status, String summary) {
        AgentCsActionLog row = new AgentCsActionLog();
        row.setId(7L);
        row.setStatus(status);
        row.setResultSummary(summary);
        lenient().when(actionLogMapper.selectOne(any())).thenReturn(row);
    }

    /** 捕获审计 INSERT 实体。 */
    private AgentCsAuditLog capturedAudit() {
        ArgumentCaptor<AgentCsAuditLog> captor = ArgumentCaptor.forClass(AgentCsAuditLog.class);
        verify(auditLogMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
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
        // 拦截也要留痕(GUARD_DENY)
        AgentCsAuditLog audit = capturedAudit();
        assertEquals("GUARD_DENY", audit.getEventType());
        assertEquals("DENIED", audit.getResultCode());
    }

    @Test
    void readToolPassesThroughWithoutAudit() {
        stubDelegateResult();
        AgentTool guarded = service.decorator(ctx(false), Map.of("refund_order", def("READ", null, null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(block));
        verify(auditLogMapper, never()).insert(any(AgentCsAuditLog.class));
    }

    @Test
    void writeWithIdentityBindingRunsAndAudits() {
        stubDelegateResult();
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", "refund:{orderNo}", null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(block));
        // 幂等占位(insert 回填 id) + 收尾(update) + 审计(insert)
        verify(actionLogMapper).insert(any(AgentCsActionLog.class));
        verify(actionLogMapper).update(any(), any());
        AgentCsAuditLog audit = capturedAudit();
        assertEquals("TOOL_CALL", audit.getEventType());
        // ToolResultBlock.text() 默认 state=RUNNING,审计原样落 state
        assertEquals("RUNNING", audit.getResultCode());
        assertTrue(audit.getDetail().contains("result=RUNNING"));
    }

    @Test
    void duplicateActionKeyReplaysInsteadOfExecuting() {
        stubDelegateResult();
        stubExistingLog("DONE", "退款已受理");
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", "refund:{orderNo}", null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertTrue(CsToolGuardService.CsToolGuard.extractSummary(block).contains("已拦截"));
        verify(delegate, never()).callAsync(any());
    }

    @Test
    void failedActionAllowsRetry() {
        stubDelegateResult();
        stubExistingLog("FAILED", null);
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", "refund:{orderNo}", null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertEquals("退款已受理", CsToolGuardService.CsToolGuard.extractSummary(block));
        // FAILED 占位复用:重置 RUNNING(reserve) + 执行后收尾(finish),共 2 次 update
        verify(actionLogMapper, org.mockito.Mockito.times(2)).update(any(), any());
        verify(actionLogMapper, never()).insert(any(AgentCsActionLog.class));
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

    @Test
    void readErrorStateIsAuditedWithResultCodeAndDuration() {
        stubDelegateResult();
        lenient().when(delegate.callAsync(any())).thenReturn(Mono.just(
            ToolResultBlock.builder().id("t1").name("refund_order")
                .output(io.agentscope.core.message.TextBlock.builder().text("失败").build())
                .state(ToolResultState.ERROR).build()));
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("READ", null, null)))
            .apply(delegate);

        guarded.callAsync(param).block();

        AgentCsAuditLog audit = capturedAudit();
        assertEquals("ERROR", audit.getResultCode(), "READ 失败必须审计结果码");
        assertTrue(audit.getDurationMs() != null, "结构化列 duration_ms 必须落库");
        assertTrue(audit.getDetail().contains("result=ERROR"), "detail 必须带结果码");
        assertTrue(audit.getDetail().contains("ms="), "detail 必须带耗时");
    }

    @Test
    void readSuccessStateIsStillNotAudited() {
        stubDelegateResult();
        lenient().when(delegate.callAsync(any())).thenReturn(Mono.just(
            ToolResultBlock.builder().id("t2").name("refund_order")
                .output(io.agentscope.core.message.TextBlock.builder().text("成功").build())
                .state(ToolResultState.SUCCESS).build()));
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("READ", null, null)))
            .apply(delegate);

        guarded.callAsync(param).block();

        verify(auditLogMapper, never()).insert(any(AgentCsAuditLog.class));
    }

    @Test
    void writeSuccessAuditCarriesStateAndDuration() {
        stubDelegateResult();
        lenient().when(delegate.callAsync(any())).thenReturn(Mono.just(
            ToolResultBlock.builder().id("t3").name("refund_order")
                .output(io.agentscope.core.message.TextBlock.builder().text("受理").build())
                .state(ToolResultState.SUCCESS).build()));
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("WRITE", null, null)))
            .apply(delegate);

        guarded.callAsync(param).block();

        AgentCsAuditLog audit = capturedAudit();
        assertEquals("SUCCESS", audit.getResultCode());
        assertTrue(audit.getDurationMs() != null);
        assertTrue(audit.getDetail().contains("ms="));
    }

    @Test
    void readExceptionIsAuditedAndConvertedToErrorBlock() {
        stubDelegateResult();
        lenient().when(delegate.callAsync(any())).thenReturn(Mono.error(new IllegalStateException("boom")));
        AgentTool guarded = service.decorator(ctx(true), Map.of("refund_order", def("READ", null, null)))
            .apply(delegate);

        ToolResultBlock block = guarded.callAsync(param).block();

        assertTrue(CsToolGuardService.CsToolGuard.extractSummary(block).contains("执行失败"));
        AgentCsAuditLog audit = capturedAudit();
        assertEquals("ERROR", audit.getResultCode());
        assertTrue(audit.getDetail().contains("boom"), "异常消息进 extra,便于排障");
    }
}
