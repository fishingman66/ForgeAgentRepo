package com.forgeagent.agent.api.customerservice;

import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agent.service.support.ChatContextAssembler;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * B4:MCP 工具证据网关单测。锁四件事:
 * ① Agent 无 MCP 绑定时 catalog 为空、call 返回"未绑定";② Agent 缺失/工作空间不符拒绝;
 * ③ 正常路径按 (server, tool) 建连调用并提取文本(静态装配 mock,不真连);
 * ④ 调用异常返回失败结果不抛出。
 *
 * <p>McpClientWrapper 为框架抽象类(可 mock);buildMcpWrapper 为静态方法,用 mockStatic。
 * 两者均为构造/编排层 mock,不触发真实网络。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class McpEvidenceGatewayTest {

    @Mock private AgentConfigCacheService agentConfigCache;
    @Mock private ChatContextAssembler contextAssembler;

    private McpEvidenceGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new McpEvidenceGateway(agentConfigCache, contextAssembler);
    }

    private AgentInfo agent(String mcpIds) {
        AgentInfo agent = new AgentInfo();
        agent.setId(100L);
        agent.setWorkspaceId(9L);
        agent.setMcpIdsStr(mcpIds);
        return agent;
    }

    private static ToolAssembler.McpDef crmDef() {
        return new ToolAssembler.McpDef(1L, "crm", "sse", "http://mcp.internal:9001/sse",
            null, 30, "auto", 60);
    }

    @Test
    void noBindingReturnsEmptyCatalogAndFailCall() {
        when(agentConfigCache.getAgentById(100L)).thenReturn(agent(""));
        when(contextAssembler.splitIds("")).thenReturn(List.of());

        assertTrue(gateway.catalog(100L, 9L).isEmpty());
        McpEvidenceGateway.McpCallResult r = gateway.call(100L, 9L, "crm", "t", Map.of(), 60);
        assertFalse(r.success());
        assertTrue(r.error().contains("未绑定"));
    }

    @Test
    void missingOrForeignAgentRejected() {
        when(agentConfigCache.getAgentById(100L)).thenReturn(null);
        assertTrue(gateway.catalog(100L, 9L).isEmpty());
        assertFalse(gateway.call(100L, 9L, "crm", "t", Map.of(), 60).success());

        // 工作空间不符:防止跨空间借用 Agent 的 MCP 绑定
        AgentInfo foreign = agent("1");
        foreign.setWorkspaceId(8L);
        when(agentConfigCache.getAgentById(100L)).thenReturn(foreign);
        when(contextAssembler.splitIds("1")).thenReturn(List.of(1L));
        assertTrue(gateway.catalog(100L, 9L).isEmpty());
    }

    @Test
    void blankServerOrToolRejectedWithoutAssembly() {
        McpEvidenceGateway.McpCallResult r = gateway.call(100L, 9L, " ", "t", Map.of(), 60);
        assertFalse(r.success());
        r = gateway.call(100L, 9L, "crm", "", Map.of(), 60);
        assertFalse(r.success());
    }

    @Test
    void callSuccessExtractsTextFromToolResult() throws Exception {
        when(agentConfigCache.getAgentById(100L)).thenReturn(agent("1"));
        when(contextAssembler.splitIds("1")).thenReturn(List.of(1L));
        when(contextAssembler.loadMcpDefs(List.of(1L), 9L)).thenReturn(List.of(crmDef()));

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        io.modelcontextprotocol.spec.McpSchema.Tool tool =
            mock(io.modelcontextprotocol.spec.McpSchema.Tool.class);
        when(tool.name()).thenReturn("query_order");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(tool)));
        io.modelcontextprotocol.spec.McpSchema.CallToolResult callResult =
            mock(io.modelcontextprotocol.spec.McpSchema.CallToolResult.class);
        when(wrapper.callTool(any(), any())).thenReturn(Mono.just(callResult));
        io.agentscope.core.message.ToolResultBlock block =
            io.agentscope.core.message.ToolResultBlock.builder().output(List.of(
                io.agentscope.core.message.TextBlock.builder().text("订单已退款").build())).build();

        try (org.mockito.MockedStatic<ToolAssembler> asm = mockStatic(ToolAssembler.class);
             org.mockito.MockedStatic<io.agentscope.core.tool.mcp.McpContentConverter> conv =
                 mockStatic(io.agentscope.core.tool.mcp.McpContentConverter.class)) {
            asm.when(() -> ToolAssembler.buildMcpWrapper(any())).thenReturn(wrapper);
            conv.when(() -> io.agentscope.core.tool.mcp.McpContentConverter
                .convertCallToolResult(callResult)).thenReturn(block);

            McpEvidenceGateway.McpCallResult r =
                gateway.call(100L, 9L, "CRM", "query_order", Map.of("orderId", "A1"), 60);

            assertTrue(r.success(), r.error());
            assertEquals("订单已退款", r.content());
        }
    }

    @Test
    void callFailureReturnsFailResultNotThrow() throws Exception {
        when(agentConfigCache.getAgentById(100L)).thenReturn(agent("1"));
        when(contextAssembler.splitIds("1")).thenReturn(List.of(1L));
        when(contextAssembler.loadMcpDefs(List.of(1L), 9L)).thenReturn(List.of(crmDef()));

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.initialize()).thenReturn(Mono.error(new IllegalStateException("连接拒绝")));

        try (org.mockito.MockedStatic<ToolAssembler> asm = mockStatic(ToolAssembler.class)) {
            asm.when(() -> ToolAssembler.buildMcpWrapper(any())).thenReturn(wrapper);

            McpEvidenceGateway.McpCallResult r =
                gateway.call(100L, 9L, "crm", "query_order", Map.of(), 60);

            assertFalse(r.success());
            assertTrue(r.error().contains("连接拒绝"));
        }
    }
}
