package com.forgeagent.common.mcp.core;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * MCP 资源读取结果。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public record McpResourceReadResult(
    String serverName,
    List<McpSchema.ResourceContents> contents
) {

    /**
     * 将 MCP SDK 资源读取结果转换为带服务端名称的项目结果对象。
     *
     * @param serverName MCP 服务端名称
     * @param result     MCP SDK 资源读取结果
     * @return MCP 资源读取结果
     */
    public static McpResourceReadResult of(String serverName, McpSchema.ReadResourceResult result) {
        return new McpResourceReadResult(serverName, result.contents());
    }
}
