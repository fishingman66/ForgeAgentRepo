package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
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
 * Toolkit 为有状态对象,每次请求独立构建。MCP 连接默认经 {@link McpConnectionPool}
 * 跨请求复用(docs-08 A4),池开关关闭时回退为每请求独立连接、请求结束关闭。</p>
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
     * 08方案 / A3:请求级装配上下文(随装配传递给各 ToolProvider)
     */
    public record BuildContext(List<Map<String, Object>> sqlCollector) {
    }

    /**
     * 08方案 / A2:技能快照(懒加载目录一行 + load_skill 白名单)
     */
    public record SkillDef(String name, String description) {
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
    /** 08方案 / A2:技能服务(load_skill 按需加载手册全文) */
    private final com.forgeagent.agent.service.IAgentSkillService skillService;
    private final AgentDatasourceMapper datasourceMapper;
    private final com.forgeagent.rag.mapper.AgentKbChunkMapper kbChunkMapper;
    private final com.forgeagent.rag.service.IAgentKnowledgeService knowledgeService;
    /** P3 / M1: L1 工作记忆(run 作用域)与会话配置 */
    private final RunWorkingMemory workingMemory;
    private final com.forgeagent.agent.config.AgentChatProperties chatProperties;
    /** 08方案 / A4:MCP 连接池(跨请求复用,可经配置关闭回退) */
    private final McpConnectionPool mcpPool;
    /** 08方案 / A3:平台工具 Provider 注册表(按 toolType 路由,新增类型零改本类) */
    private final Map<String, ToolProvider> toolProviders;
    /** C4 异步 HITL:待执行工具注册表(旧构造器传 null,仅同步 ask 路径) */
    private final PendingApprovalRegistry pendingApprovalRegistry;

    public ToolAssembler(IAgentApprovalService approvalService, SqlQueryService sqlQueryService,
                         AgentDatasourceMapper datasourceMapper,
                         com.forgeagent.rag.mapper.AgentKbChunkMapper kbChunkMapper,
                         com.forgeagent.rag.service.IAgentKnowledgeService knowledgeService,
                         RunWorkingMemory workingMemory,
                         com.forgeagent.agent.config.AgentChatProperties chatProperties,
                         McpConnectionPool mcpPool,
                         List<ToolProvider> providerList,
                         com.forgeagent.agent.service.IAgentSkillService skillService) {
        this.approvalService = approvalService;
        this.sqlQueryService = sqlQueryService;
        this.skillService = skillService;
        this.datasourceMapper = datasourceMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.knowledgeService = knowledgeService;
        this.workingMemory = workingMemory;
        this.chatProperties = chatProperties;
        this.mcpPool = mcpPool;
        this.toolProviders = providerList == null ? Map.of() : providerList.stream()
            .collect(java.util.stream.Collectors.toMap(ToolProvider::type, p -> p));
        this.pendingApprovalRegistry = null;
    }

    /** C4:Spring 注入带待执行注册表的完整构造器(旧构造器保留供表征测试使用)。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ToolAssembler(IAgentApprovalService approvalService, SqlQueryService sqlQueryService,
                         AgentDatasourceMapper datasourceMapper,
                         com.forgeagent.rag.mapper.AgentKbChunkMapper kbChunkMapper,
                         com.forgeagent.rag.service.IAgentKnowledgeService knowledgeService,
                         RunWorkingMemory workingMemory,
                         com.forgeagent.agent.config.AgentChatProperties chatProperties,
                         McpConnectionPool mcpPool,
                         List<ToolProvider> providerList,
                         com.forgeagent.agent.service.IAgentSkillService skillService,
                         PendingApprovalRegistry pendingApprovalRegistry) {
        this.approvalService = approvalService;
        this.sqlQueryService = sqlQueryService;
        this.skillService = skillService;
        this.datasourceMapper = datasourceMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.knowledgeService = knowledgeService;
        this.workingMemory = workingMemory;
        this.chatProperties = chatProperties;
        this.mcpPool = mcpPool;
        this.toolProviders = providerList == null ? Map.of() : providerList.stream()
            .collect(java.util.stream.Collectors.toMap(ToolProvider::type, p -> p));
        this.pendingApprovalRegistry = pendingApprovalRegistry;
    }

    /**
     * 构建工具箱:平台 http/sandbox/sql 工具 + MCP 服务器全部工具 + 会话文件读取工具(经审批门包装)
     *
     * <p>单个 MCP 服务器连接失败不阻断请求,跳过并告警。
     * sql 工具绑定数据源缺失或停用时跳过并告警。</p>
     *
     * @param sqlCollector 问数 SQL 执行明细收集器(可为空;SqlQueryTool 执行时写入)
     * @param attachments  本轮文件附件(非空时注册 read_attachment 工具)
     * @param skills       已安装技能快照(非空时注册 load_skill 工具;目录在服务层拼入 system prompt)
     */
    public Assembly build(List<ToolDef> toolDefs, List<McpDef> mcpDefs, ApprovalGateTool.ApprovalContext approvalCtx,
                          List<Map<String, Object>> sqlCollector,
                          List<AgentRunner.RunnerContext.AttachmentRef> attachments,
                          List<SkillDef> skills,
                          java.util.function.UnaryOperator<AgentTool> csGuardDecorator) {
        Toolkit toolkit = new Toolkit();
        List<AutoCloseable> closables = new ArrayList<>();
        RegisterOpts opts = registerOpts(approvalCtx);
        if (opts.runId() != null) {
            // P3/M1:工作记忆取回工具;自身不参与压缩,避免递归
            registerIfAbsent(toolkit, new RecallWorkingMemoryTool(workingMemory, opts.runId(),
                intValue(chatProperties.getWorkingMemoryRecallLimitChars(), 4000)),
                "recall_working_memory", opts, csGuardDecorator);
        }
        // P0/A4:内置时间工具,让模型经工具调用获取实时北京时间(不污染 system prompt 前缀)
        registerIfAbsent(toolkit, new CurrentTimeTool(), "get_current_time", opts, csGuardDecorator);
        if (attachments != null && !attachments.isEmpty()) {
            // 只读会话临时索引,不发外部请求,不经审批门
            registerIfAbsent(toolkit, new ReadAttachmentTool(attachments, kbChunkMapper, knowledgeService),
                "read_attachment", opts, csGuardDecorator);
        }
        if (skills != null && !skills.isEmpty()) {
            // 08方案 / A2:懒加载技能工具;只读平台数据,免审批门;目录由服务层拼进 system prompt
            Long skillAgentId = approvalCtx == null ? null : approvalCtx.agentId();
            registerIfAbsent(toolkit, new LoadSkillTool(skillService, skillAgentId, skills),
                "load_skill", opts, csGuardDecorator);
        }
        if (toolDefs != null) {
            BuildContext buildCtx = new BuildContext(sqlCollector);
            for (ToolDef def : toolDefs) {
                // 08方案 / A3:按 toolType 路由到 Provider;未匹配类型跳过并告警
                ToolProvider provider = toolProviders.get(
                    StringUtils.defaultIfBlank(def.toolType(), "http"));
                if (provider == null) {
                    log.warn("[ToolAssembler] 未找到工具类型 [{}] 的 Provider,跳过注册: {}",
                        def.toolType(), def.name());
                    continue;
                }
                List<AgentTool> provided;
                try {
                    provided = provider.provide(def, buildCtx);
                } catch (Exception e) {
                    log.warn("[ToolAssembler] 工具 [{}] 装配失败,已跳过: {}",
                        def.name(), e.getMessage());
                    continue;
                }
                if (provided == null || provided.isEmpty()) {
                    continue;
                }
                for (AgentTool inner : provided) {
                    // 免审批标记(如 sql schema 只读元数据工具):与拆分前行为一致,避免重复弹窗
                    AgentTool wrapped = inner instanceof ApprovalExempt
                        ? inner
                        : gate(inner, "platform-" + provider.type(), "",
                            def.approvalMode(), def.approvalTimeout(), approvalCtx);
                    registerIfAbsent(toolkit, wrapped, inner.getName(), opts, csGuardDecorator);
                }
            }
        }
        if (mcpDefs != null) {
            boolean poolEnabled = Boolean.TRUE.equals(chatProperties.getMcpPoolEnabled());
            for (McpDef def : mcpDefs) {
                // 08方案 / A4:先取池内连接(listTools 兼作健康校验),未命中再新建
                McpConnectionPool.Pooled pooled = poolEnabled
                    ? mcpPool.acquire(mcpPool.fingerprint(def)) : null;
                if (pooled != null) {
                    registerMcpTools(toolkit, def, pooled.wrapper(), pooled.tools(), opts, approvalCtx, csGuardDecorator);
                    continue;
                }
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
                    if (poolEnabled) {
                        // 池内连接不能进 closables,否则请求结束 Assembly.close() 会关掉它
                        mcpPool.offer(mcpPool.fingerprint(def),
                            new McpConnectionPool.Pooled(wrapper, mcpTools));
                    } else {
                        closables.add(wrapper);
                    }
                    registerMcpTools(toolkit, def, wrapper, mcpTools, opts, approvalCtx, csGuardDecorator);
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
     * 注册单个 MCP 服务器发现的全部工具(经审批门)
     */
    private void registerMcpTools(Toolkit toolkit, McpDef def, McpClientWrapper wrapper,
                                  List<McpSchema.Tool> mcpTools, RegisterOpts opts,
                                  ApprovalGateTool.ApprovalContext approvalCtx,
                                  java.util.function.UnaryOperator<AgentTool> csGuardDecorator) {
        for (McpSchema.Tool t : mcpTools) {
            McpAgentTool mcpTool = new McpAgentTool(t, wrapper);
            registerIfAbsent(toolkit, gate(mcpTool, "mcp", def.name(),
                def.approvalMode(), def.approvalTimeout(), approvalCtx), t.name(), opts, csGuardDecorator);
        }
        log.info("[ToolAssembler] MCP 服务器 [{}] 注册 {} 个工具", def.name(), mcpTools.size());
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
            new java.util.concurrent.CopyOnWriteArrayList<>(), List.of(), List.of(), null);
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
    private void registerIfAbsent(Toolkit toolkit, AgentTool tool, String expectName, RegisterOpts opts,
                                  java.util.function.UnaryOperator<AgentTool> csGuardDecorator) {
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
        if (csGuardDecorator != null) {
            // C2:客服护栏挂审批门之后(最后一道);map 未命中的工具原样透传
            registered = csGuardDecorator.apply(registered);
        }
        toolkit.registerAgentTool(registered);
    }

    private static int intValue(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private AgentTool gate(AgentTool inner, String source, String serverName,
                           String mode, Integer timeout, ApprovalGateTool.ApprovalContext ctx) {
        return new ApprovalGateTool(inner, source, serverName, mode, timeout, ctx,
            approvalService, pendingApprovalRegistry, null);
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
