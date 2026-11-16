package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;

import java.util.List;

/**
 * 08方案 / A3:平台工具装配 SPI(Service Definition + Provider,对标 DSH Capability Seam)。
 *
 * <p>每个 {@code agent_tool.tool_type} 对应一个 Provider 实现,Spring 注入 {@code List<ToolProvider>}
 * 后由 {@link ToolAssembler} 按 type 路由;新增工具类型零改装配核心。
 * 租户只能组合既有工具,Provider 实现属平台代码——这是「不做可执行插件」决策的边界。</p>
 *
 * <p>注意:MCP 不走本 SPI(输入是 McpDef 且连接已由 {@link McpConnectionPool} 承担),
 * 装配段保留在 ToolAssembler 内。</p>
 *
 * @author ForgeAgent
 */
public interface ToolProvider {

    /** 平台工具类型标识,与 agent_tool.tool_type 对应 */
    String type();

    /**
     * 装配一条工具定义为内核工具(可返回配套工具,如 sql 的 schema 明细工具)。
     *
     * @param def 平台工具定义快照
     * @param ctx 装配上下文(请求级共享对象,如 SQL 明细收集器)
     * @return 待注册工具列表;null 或空表示跳过(定义无效/依赖缺失,由实现负责告警)
     */
    List<AgentTool> provide(ToolAssembler.ToolDef def, ToolAssembler.BuildContext ctx);
}
