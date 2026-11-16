package com.forgeagent.rag.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 08方案 / A5:对外 MCP 端点的外部工具 Provider SPI。
 *
 * <p>对外 Streamable HTTP 端点(/mcp)由 forge-rag 承载,但平台其他域的工具
 * 不应在编译期被 rag 依赖——外部工具经本 SPI 注入(Spring 收集所有实现),
 * forge-agent 等模块各自提供。工具调用与 knowledge 系列走同一套
 * X-MCP-Token 常量时间校验与 {rows:[...]} 输出形态。</p>
 *
 * <p>安全口径:当前单 token = 平台全量只读(与 knowledge 系列一致);
 * token→工作空间粒度鉴权属后续增强,Provider 实现内不得返回跨域敏感数据。</p>
 *
 * @author ForgeAgent
 */
public interface McpExternalToolProvider {

    /** 本 Provider 支持的工具名集合(tools/call 的 name 路由依据) */
    Set<String> supportedTools();

    /**
     * tools/list 定义:返回本 Provider 全部工具的定义(name/description/inputSchema)
     *
     * @param om 共享 ObjectMapper(rag 端点持有,fasterxml 版本与本模块一致)
     */
    java.util.List<JsonNode> definitions(ObjectMapper om);

    /**
     * tools/call 执行:返回 {rows:[...]} 形态的结果(与内置工具同构)
     *
     * @throws IllegalArgumentException 业务校验类错误(消息可安全透出给客户端)
     */
    com.fasterxml.jackson.databind.node.ObjectNode invoke(ObjectMapper om, String toolName, JsonNode args)
        throws IllegalArgumentException;
}
