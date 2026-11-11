package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * MCP 工具的 AgentScope 适配器
 *
 * <p>直接实现内核 AgentTool 接口(mcp 2.0.0 兼容路径,已实测):
 * schema 直接透传 MCP 的 inputSchema Map,执行经 wrapper.callTool 并由
 * {@link McpContentConverter} 转换结果。不使用 Toolkit.registerMcpClient
 * (其内部对 listTools 的重复订阅在部分服务端通知场景下会挂起)。</p>
 *
 * @author ForgeAgent
 */
record McpAgentTool(McpSchema.Tool tool, McpClientWrapper wrapper) implements AgentTool {

    @Override
    public String getName() {
        return tool.name();
    }

    @Override
    public String getDescription() {
        return tool.description() == null ? "" : tool.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return tool.inputSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return wrapper.callTool(tool.name(), input)
            .map(McpContentConverter::convertCallToolResult);
    }
}
