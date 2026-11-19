package com.forgeagent.agent.service.runner;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4 异步 HITL:ask_async 模式的行为契约。
 *
 * <p>核心语义:创建审批单 + 登记待执行任务后立即回执,绝不在审批前执行 delegate;
 * 同步 ask / auto / deny 分支由既有 ToolAssembler 表征测试覆盖,本类只锁新增路径。</p>
 */
@Tag("dev")
class ApprovalGateToolAsyncTest {

    @Test
    void askAsync_registersTaskAndReturnsImmediatelyWithoutExecuting() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("refund");
        when(delegate.callAsync(any(ToolCallParam.class)))
            .thenReturn(Mono.just(ToolResultBlock.text("executed")));

        com.forgeagent.agent.service.IAgentApprovalService approvalService =
            mock(com.forgeagent.agent.service.IAgentApprovalService.class);
        when(approvalService.create(any(), any(), any(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(777L);

        PendingApprovalRegistry registry = new PendingApprovalRegistry();
        ApprovalGateTool.ApprovalContext ctx =
            new ApprovalGateTool.ApprovalContext(11L, 22L, "run-1");
        ApprovalGateTool gate = new ApprovalGateTool(delegate, "platform-http", "",
            "ask_async", 120, ctx, approvalService, registry, null);

        ToolCallParam param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of("orderNo", "A1"));

        ToolResultBlock result = gate.callAsync(param).block();
        assertNotNull(result);
        String text = String.valueOf(result.getOutput());
        assertTrue(text.contains("已提交") || text.contains("等待"), "应立即返回等待确认回执");

        // 审批前不得执行真实工具
        verify(delegate, never()).callAsync(any(ToolCallParam.class));

        // 任务已按 approvalId 登记
        PendingApprovalRegistry.PendingTask task = registry.remove(777L);
        assertNotNull(task, "待执行任务应已登记");
        assertEquals(delegate, task.delegate());
        assertEquals("run-1", task.runId());
        assertEquals(0, registry.size(), "取出后应清空登记");
    }

    @Test
    void askAsync_withoutRegistryStillReturnsReceipt() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("refund");
        com.forgeagent.agent.service.IAgentApprovalService approvalService =
            mock(com.forgeagent.agent.service.IAgentApprovalService.class);
        when(approvalService.create(any(), any(), any(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(888L);

        ApprovalGateTool gate = new ApprovalGateTool(delegate, "mcp", "srv",
            "ask_async", 120, new ApprovalGateTool.ApprovalContext(1L, 2L, "run-2"),
            approvalService, null, null);
        ToolCallParam param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of());

        ToolResultBlock result = gate.callAsync(param).block();
        assertNotNull(result, "无注册表时仍应回执,不抛异常");
        verify(delegate, never()).callAsync(any(ToolCallParam.class));
    }
}
