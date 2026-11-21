package com.forgeagent.agent.api.customerservice;

import com.forgeagent.agent.service.support.ChatContextAssembler;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B4:MCP 工具证据网关——给客服根因调查(RCA)提供跨模块的 MCP 工具调用能力。
 *
 * <p>复用 forge-agent 既有的装配与连接生命周期({@link ToolAssembler#buildMcpWrapper} +
 * initialize/listTools/close,与装配阶段同一口径),不新建连接管理。能力有二:</p>
 * <ul>
 *   <li>{@link #catalog}:枚举 Agent 已绑定 MCP 服务器的工具目录(供提示词列出可选工具);</li>
 *   <li>{@link #call}:按 (serverName, toolName) 执行一次工具调用并返回文本结果。</li>
 * </ul>
 *
 * <p>失败语义:catalog 跳过连接失败的服务器(不拖垮整个目录);call 返回失败结果不抛异常,
 * 由调用方写失败证据行。Agent 无 MCP 绑定时 catalog 为空列表、call 返回未找到。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpEvidenceGateway {

    private final AgentConfigCacheService agentConfigCache;
    private final ChatContextAssembler contextAssembler;

    /** 工具目录条目 */
    public record CatalogEntry(String server, String tool, String description) {
    }

    /** 工具调用结果 */
    public record McpCallResult(boolean success, String content, String error) {

        public static McpCallResult ok(String content) {
            return new McpCallResult(true, content, null);
        }

        public static McpCallResult fail(String error) {
            return new McpCallResult(false, null, error);
        }
    }

    /**
     * 枚举 Agent 已绑定(启用且对工作空间可读)的 MCP 服务器工具目录。
     * 单个服务器连接失败只记日志跳过;无绑定返回空列表。
     */
    public List<CatalogEntry> catalog(Long agentId, Long workspaceId) {
        List<ToolAssembler.McpDef> defs = resolveDefs(agentId, workspaceId);
        if (defs.isEmpty()) {
            return List.of();
        }
        List<CatalogEntry> entries = new ArrayList<>();
        for (ToolAssembler.McpDef def : defs) {
            McpClientWrapper wrapper = null;
            try {
                wrapper = ToolAssembler.buildMcpWrapper(def);
                wrapper.initialize().block(Duration.ofSeconds(30));
                List<McpSchema.Tool> tools = wrapper.listTools().block(Duration.ofSeconds(30));
                if (tools == null) {
                    continue;
                }
                for (McpSchema.Tool t : tools) {
                    entries.add(new CatalogEntry(def.name(),
                        truncate(t.name(), 120), truncate(t.description(), 200)));
                }
            } catch (Exception e) {
                log.warn("[RCA-MCP] 服务器 [{}] 工具目录获取失败,已跳过: {}", def.name(), rootMessage(e));
            } finally {
                closeQuietly(wrapper);
            }
        }
        return entries;
    }

    /**
     * 执行一次 MCP 工具调用。目标服务器/工具不存在、连接失败、执行失败均返回失败结果。
     */
    public McpCallResult call(Long agentId, Long workspaceId, String serverName, String toolName,
                              Map<String, Object> args, Integer timeoutSeconds) {
        if (serverName == null || serverName.isBlank() || toolName == null || toolName.isBlank()) {
            return McpCallResult.fail("MCP 服务器或工具名为空");
        }
        List<ToolAssembler.McpDef> defs = resolveDefs(agentId, workspaceId);
        ToolAssembler.McpDef target = defs.stream()
            .filter(d -> serverName.equalsIgnoreCase(d.name()))
            .findFirst()
            .orElse(null);
        if (target == null) {
            return McpCallResult.fail("Agent 未绑定名为 [" + serverName + "] 的 MCP 服务器");
        }
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
            ? (target.timeoutSeconds() == null ? 30 : target.timeoutSeconds()) : timeoutSeconds;
        McpClientWrapper wrapper = null;
        try {
            wrapper = ToolAssembler.buildMcpWrapper(target);
            wrapper.initialize().block(Duration.ofSeconds(30));
            List<McpSchema.Tool> tools = wrapper.listTools().block(Duration.ofSeconds(30));
            if (tools == null || tools.stream().noneMatch(t -> toolName.equals(t.name()))) {
                return McpCallResult.fail("服务器 [" + serverName + "] 上不存在工具 [" + toolName + "]");
            }
            ToolResultBlock block = McpContentConverter.convertCallToolResult(
                wrapper.callTool(toolName, args == null ? Map.of() : args)
                    .block(Duration.ofSeconds(timeout)));
            return McpCallResult.ok(extractText(block));
        } catch (Exception e) {
            log.warn("[RCA-MCP] 调用 {}/{} 失败: {}", serverName, toolName, rootMessage(e));
            return McpCallResult.fail(rootMessage(e));
        } finally {
            closeQuietly(wrapper);
        }
    }

    /** Agent 的启用且可读 MCP 定义列表;Agent 不存在或无绑定返回空列表。 */
    private List<ToolAssembler.McpDef> resolveDefs(Long agentId, Long workspaceId) {
        if (agentId == null || workspaceId == null) {
            return List.of();
        }
        com.forgeagent.agentcore.domain.AgentInfo agent = agentConfigCache.getAgentById(agentId);
        if (agent == null || !workspaceId.equals(agent.getWorkspaceId())) {
            return List.of();
        }
        List<Long> mcpIds = contextAssembler.splitIds(agent.getMcpIdsStr());
        if (mcpIds.isEmpty()) {
            return List.of();
        }
        return contextAssembler.loadMcpDefs(mcpIds, workspaceId);
    }

    private static String extractText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null || block.getOutput().isEmpty()) {
            return "(工具无返回)";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : block.getOutput()) {
            if (item instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.isEmpty() ? "(工具无文本返回)" : sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String rootMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static void closeQuietly(McpClientWrapper wrapper) {
        if (wrapper != null) {
            try {
                wrapper.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }
}
