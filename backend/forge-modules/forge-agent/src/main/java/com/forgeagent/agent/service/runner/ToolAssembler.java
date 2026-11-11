package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentScope Toolkit 装配器
 *
 * <p>按 Agent 绑定关系把平台工具(http)与 MCP 服务器工具注册进内核 Toolkit,
 * 每个工具都经过 {@link ApprovalGateTool} 审批门(auto/ask/deny)。
 * Toolkit 与 MCP 客户端均为有状态对象,每次请求独立构建,请求结束后关闭。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class ToolAssembler {

    /**
     * 平台工具定义快照
     *
     * @param toolType http/sandbox/sql;http 参数仅 http 生效,sandbox 参数仅 sandbox 生效,
     *                 datasourceId 仅 sql 生效
     */
    public record ToolDef(Long id, String name, String description, String toolType, Long datasourceId,
                          String httpMethod, String httpUrl, String httpHeaders, String httpParams,
                          String sandboxImage, Integer sandboxTimeout, String sandboxMemory, String sandboxCpus,
                          String approvalMode, Integer approvalTimeout) {
    }

    /**
     * MCP 服务器定义快照
     */
    public record McpDef(Long id, String name, String serverType, String url, String headers,
                         Integer timeoutSeconds, String approvalMode, Integer approvalTimeout) {
    }

    /**
     * 装配结果:toolkit + 需随请求结束关闭的资源
     */
    public record Assembly(Toolkit toolkit, List<AutoCloseable> closables) implements AutoCloseable {

        @Override
        public void close() {
            for (AutoCloseable closable : closables) {
                try {
                    closable.close();
                } catch (Exception e) {
                    log.warn("[ToolAssembler] 关闭 MCP 连接失败: {}", e.getMessage());
                }
            }
        }
    }

    private final IAgentApprovalService approvalService;
    private final SqlQueryService sqlQueryService;
    private final AgentDatasourceMapper datasourceMapper;
    private final com.forgeagent.rag.mapper.AgentKbChunkMapper kbChunkMapper;
    private final com.forgeagent.rag.service.IAgentKnowledgeService knowledgeService;
    /** P3 / M1: L1 工作记忆(run 作用域)与会话配置 */
    private final RunWorkingMemory workingMemory;
    private final com.forgeagent.agent.config.AgentChatProperties chatProperties;

    public ToolAssembler(IAgentApprovalService approvalService, SqlQueryService sqlQueryService,
                         AgentDatasourceMapper datasourceMapper,
                         com.forgeagent.rag.mapper.AgentKbChunkMapper kbChunkMapper,
                         com.forgeagent.rag.service.IAgentKnowledgeService knowledgeService,
                         RunWorkingMemory workingMemory,
                         com.forgeagent.agent.config.AgentChatProperties chatProperties) {
        this.approvalService = approvalService;
        this.sqlQueryService = sqlQueryService;
        this.datasourceMapper = datasourceMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.knowledgeService = knowledgeService;
        this.workingMemory = workingMemory;
        this.chatProperties = chatProperties;
    }

    /**
     * 构建工具箱:平台 http/sandbox/sql 工具 + MCP 服务器全部工具 + 会话文件读取工具(经审批门包装)
     *
     * <p>单个 MCP 服务器连接失败不阻断请求,跳过并告警。
     * sql 工具绑定数据源缺失或停用时跳过并告警。</p>
     *
     * @param sqlCollector 问数 SQL 执行明细收集器(可为空;SqlQueryTool 执行时写入)
     * @param attachments  本轮文件附件(非空时注册 read_attachment 工具)
     */
    public Assembly build(List<ToolDef> toolDefs, List<McpDef> mcpDefs, ApprovalGateTool.ApprovalContext approvalCtx,
                          List<Map<String, Object>> sqlCollector,
                          List<AgentRunner.RunnerContext.AttachmentRef> attachments) {
        Toolkit toolkit = new Toolkit();
        List<AutoCloseable> closables = new ArrayList<>();
        RegisterOpts opts = registerOpts(approvalCtx);
        if (opts.runId() != null) {
            // P3/M1:工作记忆取回工具;自身不参与压缩,避免递归
            registerIfAbsent(toolkit, new RecallWorkingMemoryTool(workingMemory, opts.runId(),
                intValue(chatProperties.getWorkingMemoryRecallLimitChars(), 4000)),
                "recall_working_memory", opts);
        }
        // P0/A4:内置时间工具,让模型经工具调用获取实时北京时间(不污染 system prompt 前缀)
        registerIfAbsent(toolkit, new CurrentTimeTool(), "get_current_time", opts);
        if (attachments != null && !attachments.isEmpty()) {
            // 只读会话临时索引,不发外部请求,不经审批门
            registerIfAbsent(toolkit, new ReadAttachmentTool(attachments, kbChunkMapper, knowledgeService),
                "read_attachment", opts);
        }
        if (toolDefs != null) {
            for (ToolDef def : toolDefs) {
                AgentTool inner;
                if ("sandbox".equals(def.toolType())) {
                    inner = new DockerSandboxTool(new DockerSandboxTool.Config(
                        def.name(), def.description(), def.sandboxImage(),
                        def.sandboxTimeout(), def.sandboxMemory(), def.sandboxCpus()));
                } else if ("sql".equals(def.toolType())) {
                    AgentDatasource ds = def.datasourceId() == null
                        ? null : datasourceMapper.selectById(def.datasourceId());
                    if (ds == null || "1".equals(ds.getStatus())) {
                        log.warn("[ToolAssembler] SQL 工具 [{}] 绑定数据源缺失或停用,跳过注册", def.name());
                        continue;
                    }
                    inner = new SqlQueryTool(def, ds, sqlQueryService, sqlCollector);
                    // schema 明细工具与查询工具配套注册;只读元数据不经审批门,避免重复弹窗
                    registerIfAbsent(toolkit, new SqlSchemaTool(def, ds, sqlQueryService),
                        def.name() + "_schema", opts);
                } else {
                    inner = new HttpToolAdapter(new HttpToolAdapter.ToolDef(
                        def.id(), def.name(), def.description(), def.httpMethod(),
                        def.httpUrl(), def.httpHeaders(), def.httpParams()));
                }
                registerIfAbsent(toolkit, gate(inner, "platform-" + StringUtils.defaultIfBlank(def.toolType(), "http"),
                    "", def.approvalMode(), def.approvalTimeout(), approvalCtx), def.name(), opts);
            }
        }
        if (mcpDefs != null) {
            for (McpDef def : mcpDefs) {
                McpClientWrapper wrapper = null;
                try {
                    wrapper = buildMcpWrapper(def);
                    wrapper.initialize().block(Duration.ofSeconds(30));
                    List<McpSchema.Tool> mcpTools = wrapper.listTools().block(Duration.ofSeconds(30));
                    if (mcpTools == null || mcpTools.isEmpty()) {
                        log.warn("[ToolAssembler] MCP 服务器 [{}] 未发现工具", def.name());
                        wrapper.close();
                        continue;
                    }
                    for (McpSchema.Tool t : mcpTools) {
                        McpAgentTool mcpTool = new McpAgentTool(t, wrapper);
                        registerIfAbsent(toolkit, gate(mcpTool, "mcp", def.name(),
                            def.approvalMode(), def.approvalTimeout(), approvalCtx), t.name(), opts);
                    }
                    closables.add(wrapper);
                    log.info("[ToolAssembler] MCP 服务器 [{}] 注册 {} 个工具", def.name(), mcpTools.size());
                } catch (Exception e) {
                    log.warn("[ToolAssembler] MCP 服务器 [{}] 连接失败,已跳过: {}", def.name(), rootMessage(e));
                    if (wrapper != null) {
                        try {
                            wrapper.close();
                        } catch (Exception ignore) {
                            // ignore
                        }
                    }
                }
            }
        }
        return new Assembly(toolkit, closables);
    }

    /**
     * 工作流节点专用装配(P1 / B4):只装配平台工具,不带会话上下文与 MCP。
     *
     * <p>工作流节点由画布显式触发,不存在人工审批等待的场景,因此统一把审批模式
     * 归一为 auto,避免审批单因缺少 sessionId 挂起。</p>
     */
    public Assembly buildForWorkflow(List<ToolDef> toolDefs) {
        List<ToolDef> normalized = toolDefs.stream()
            .map(d -> new ToolDef(d.id(), d.name(), d.description(), d.toolType(), d.datasourceId(),
                d.httpMethod(), d.httpUrl(), d.httpHeaders(), d.httpParams(),
                d.sandboxImage(), d.sandboxTimeout(), d.sandboxMemory(), d.sandboxCpus(),
                "auto", d.approvalTimeout()))
            .toList();
        return build(normalized, List.of(),
            new ApprovalGateTool.ApprovalContext(null, null, null),
            new java.util.concurrent.CopyOnWriteArrayList<>(), List.of());
    }

    /**
     * P3 / M1:L1 压缩参数按轮次构造(ToolAssembler 是单例,不能持有请求级状态)。
     * 工作流路径 roundId 为空,或开关关闭时不启用压缩,也不注册取回工具。
     */
    private RegisterOpts registerOpts(ApprovalGateTool.ApprovalContext approvalCtx) {
        String runId = approvalCtx == null ? null : approvalCtx.roundId();
        if (runId == null || !Boolean.TRUE.equals(chatProperties.getWorkingMemoryEnabled())) {
            return new RegisterOpts(null, 0, 0);
        }
        return new RegisterOpts(runId,
            intValue(chatProperties.getWorkingMemoryThresholdTokens(), 800),
            intValue(chatProperties.getWorkingMemoryPreviewChars(), 200));
    }

    private record RegisterOpts(String runId, int thresholdTokens, int previewChars) {
    }

    /**
     * 注册时统一套上工作记忆装饰器;取回工具本身不包装,避免把「取回结果」再次压缩。
     */
    private void registerIfAbsent(Toolkit toolkit, AgentTool tool, String expectName, RegisterOpts opts) {
        String name = tool.getName();
        if (toolkit.getTool(name) != null) {
            log.warn("[ToolAssembler] 工具 [{}] 重名,跳过注册(来源: {})", name, expectName);
            return;
        }
        AgentTool registered = tool;
        if (opts.runId() != null && !(tool instanceof RecallWorkingMemoryTool)) {
            registered = new WorkingMemoryGuard(tool, workingMemory, opts.runId(),
                opts.thresholdTokens(), opts.previewChars());
        }
        toolkit.registerAgentTool(registered);
    }

    private static int intValue(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private AgentTool gate(AgentTool inner, String source, String serverName,
                           String mode, Integer timeout, ApprovalGateTool.ApprovalContext ctx) {
        return new ApprovalGateTool(inner, source, serverName, mode, timeout, ctx, approvalService);
    }

    /**
     * 按 MCP 定义构建 AgentScope 客户端(sse / streamable_http)
     */
    public static McpClientWrapper buildMcpWrapper(McpDef def) {
        McpClientBuilder builder = McpClientBuilder.create(def.name())
            .timeout(Duration.ofSeconds(def.timeoutSeconds() == null ? 30 : def.timeoutSeconds()))
            .initializationTimeout(Duration.ofSeconds(30));
        if ("sse".equals(def.serverType())) {
            builder.sseTransport(def.url());
        } else {
            builder.streamableHttpTransport(def.url());
        }
        if (StringUtils.isNotBlank(def.headers())) {
            Map<String, Object> headers = JsonUtils.parseMap(def.headers());
            if (headers != null) {
                headers.forEach((k, v) -> builder.header(k, String.valueOf(v)));
            }
        }
        return builder.buildSync();
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
