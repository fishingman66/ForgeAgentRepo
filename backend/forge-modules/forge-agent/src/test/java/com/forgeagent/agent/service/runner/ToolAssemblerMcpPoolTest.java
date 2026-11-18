package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 08方案 / A4:ToolAssembler MCP 装配池化行为单测。
 *
 * <p>锁定三条铁律:池内连接绝不进 Assembly.closables(否则请求结束被误关);
 * 命中池后不再重建连接(initialize 只发生一次);池开关关闭回退为请求级连接;
 * 连接失败跳过不阻断请求。静态 buildMcpWrapper 用 mockStatic 拦截。</p>
 */
@Tag("dev")
class ToolAssemblerMcpPoolTest {

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, "测试工具",
            Map.of("type", "object", "properties", Map.of()), null, null, null);
    }

    private ToolAssembler assembler(AgentChatProperties props, McpConnectionPool pool) {
        return new ToolAssembler(
            mock(com.forgeagent.agent.service.IAgentApprovalService.class),
            mock(SqlQueryService.class),
            mock(com.forgeagent.agent.mapper.AgentDatasourceMapper.class),
            mock(com.forgeagent.rag.mapper.AgentKbChunkMapper.class),
            mock(com.forgeagent.rag.service.IAgentKnowledgeService.class),
            mock(RunWorkingMemory.class),
            props,
            pool,
            List.of(new HttpToolProvider(), new SandboxToolProvider(),
                new SqlToolProvider(mock(com.forgeagent.agent.mapper.AgentDatasourceMapper.class),
                    mock(SqlQueryService.class))),
            mock(com.forgeagent.agent.service.IAgentSkillService.class));
    }

    private static ToolAssembler.McpDef mcpDef() {
        return new ToolAssembler.McpDef(1L, "srv", "streamable_http",
            "http://localhost:9999/mcp", null, 30, null, null);
    }

    private static McpClientWrapper healthyWrapper() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(tool("t1"))));
        return wrapper;
    }

    private static ToolAssembler.Assembly build(ToolAssembler assembler) {
        return assembler.build(null, List.of(mcpDef()),
            new ApprovalGateTool.ApprovalContext(null, null, null),
            new java.util.concurrent.CopyOnWriteArrayList<>(), List.of(), List.of(), null);
    }

    @Test
    void pooledConnectionIsNeverClosedByRequestScope() {
        AgentChatProperties props = new AgentChatProperties();
        McpConnectionPool pool = new McpConnectionPool(props);
        ToolAssembler assembler = assembler(props, pool);
        McpClientWrapper wrapper = healthyWrapper();

        try (MockedStatic<ToolAssembler> mocked = Mockito.mockStatic(ToolAssembler.class)) {
            mocked.when(() -> ToolAssembler.buildMcpWrapper(any())).thenReturn(wrapper);

            ToolAssembler.Assembly assembly = build(assembler);
            assertTrue(assembly.closables().isEmpty(), "池内连接不得进 closables");
            assertEquals(1, countTools(assembly.toolkit()), "池化路径同样要注册工具");
            assembly.close();
            verify(wrapper, never()).close();

            // 第二次请求命中池:initialize 只发生一次
            ToolAssembler.Assembly second = build(assembler);
            assertEquals(1, countTools(second.toolkit()));
            verify(wrapper, times(1)).initialize();
        }
    }

    @Test
    void poolDisabledFallsBackToRequestScopedConnection() {
        AgentChatProperties props = new AgentChatProperties();
        props.setMcpPoolEnabled(false);
        McpConnectionPool pool = new McpConnectionPool(props);
        ToolAssembler assembler = assembler(props, pool);
        McpClientWrapper wrapper = healthyWrapper();

        try (MockedStatic<ToolAssembler> mocked = Mockito.mockStatic(ToolAssembler.class)) {
            mocked.when(() -> ToolAssembler.buildMcpWrapper(any())).thenReturn(wrapper);

            ToolAssembler.Assembly assembly = build(assembler);
            assertEquals(1, assembly.closables().size(), "回退路径连接随请求关闭");
            assembly.close();
            verify(wrapper, times(1)).close();
        }
    }

    @Test
    void failedConnectionIsSkippedWithoutBlocking() {
        AgentChatProperties props = new AgentChatProperties();
        McpConnectionPool pool = new McpConnectionPool(props);
        ToolAssembler assembler = assembler(props, pool);
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.initialize()).thenReturn(Mono.error(new IllegalStateException("boom")));

        try (MockedStatic<ToolAssembler> mocked = Mockito.mockStatic(ToolAssembler.class)) {
            mocked.when(() -> ToolAssembler.buildMcpWrapper(any())).thenReturn(wrapper);

            ToolAssembler.Assembly assembly = build(assembler);
            assertEquals(0, countTools(assembly.toolkit()), "连接失败不注册任何 MCP 工具");
            assembly.close();
        }
    }

    private int countTools(Toolkit toolkit) {
        return toolkit.getTool("t1") != null ? 1 : 0;
    }
}
