package com.forgeagent.agent.service.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentTokenUsage;
import com.forgeagent.agent.domain.AgentWorkflowRun;
import com.forgeagent.agent.domain.AgentWorkflowVersion;
import com.forgeagent.agent.domain.bo.WorkflowGraphBo;
import com.forgeagent.agent.domain.bo.WorkflowTrialRunBo;
import com.forgeagent.agent.domain.vo.AgentWorkflowRunVo;
import com.forgeagent.agent.domain.vo.AgentWorkflowVersionVo;
import com.forgeagent.agent.domain.vo.WorkflowRunDetailVo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.domain.vo.WorkflowValidateVo;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.mapper.AgentWorkflowRunMapper;
import com.forgeagent.agent.mapper.AgentWorkflowVersionMapper;
import com.forgeagent.agent.service.IAgentWorkflowService;
import com.forgeagent.agent.service.workflow.WorkflowExpressionEngine;
import com.forgeagent.agent.service.workflow.WorkflowTraceCodec;
import com.forgeagent.agent.service.workflow.WorkflowGraphValidator;
import com.forgeagent.agent.service.workflow.WorkflowGraphValidator.OutEdge;
import static com.forgeagent.agent.service.workflow.WorkflowExpressionEngine.interpolate;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.agent.service.TokenUsageRecorder;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.agentcore.service.AgentScopeModelFactory;
import com.forgeagent.agent.service.runner.SandboxCodeExecutor;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agent.service.support.ChatContextAssembler;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import com.forgeagent.agent.service.workflow.run.ApprovalPauseException;
import com.forgeagent.agent.service.workflow.run.WorkflowRunContext;
import org.springframework.stereotype.Component;

/**
 * 工作流节点执行器集合:按节点类型分发并执行各类型节点。
 *
 * <p>从 {@code AgentWorkflowServiceImpl} 整体抽出,方法实现与原代码逐字一致,
 * 只是把原先散落在主类里的字段依赖改为构造器注入。图编排层只通过
 * {@link #executeNode} 与之交互,不再关心模型/工具/知识库/沙箱/HTTP 的细节。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class WorkflowNodeExecutors {

    private final AgentModelMapper modelMapper;
    private final AgentApprovalMapper approvalMapper;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final SandboxCodeExecutor sandboxCodeExecutor;
    private final IAgentKnowledgeService knowledgeService;
    private final ChatContextAssembler contextAssembler;
    private final ToolAssembler toolAssembler;
    private final com.forgeagent.agent.config.AgentRuntimeProperties runtimeProperties;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 与图校验器共用的 HTTP 方法白名单 */
    private static final Set<String> HTTP_METHODS = WorkflowGraphValidator.HTTP_METHODS;

    /**
     * 执行单个节点并注册输出变量
     *
     * @return 条件节点返回选中的分支名称,其他节点返回 null
     */
    public String executeNode(JsonNode node, WorkflowTrialRunVo.NodeTrace t,
                               Map<String, String> vars, WorkflowRunContext ctx) {
        String type = node.path("type").asText("");
        JsonNode config = node.path("config");
        switch (type) {
            case "start" -> {
                // 试运行输入 JSON 的每个键注册为 start.<key>
                JsonNode parsed = parseJson(ctx.inputs());
                parsed.fieldNames().forEachRemaining(key ->
                    vars.put("start." + key, parsed.path(key).asText("")));
                t.setStatus("success");
                return null;
            }
            case "llm" -> {
                executeLlmNode(config, t, vars, ctx.agent(), ctx.run());
                return null;
            }
            case "loop" -> {
                executeLoopNode(config, t, vars, ctx.agent(), ctx.run());
                return null;
            }
            case "code" -> {
                executeCodeNode(config, t, vars);
                return null;
            }
            case "http" -> {
                executeHttpNode(config, t, vars);
                return null;
            }
            case "knowledge" -> {
                // P1/B4:接 forge-rag 真实混合检索,命中内容写入节点输出变量;
                // W1/体检 S2:无登录上下文链路走内部入口,并按工作流归属空间做归属校验
                executeKnowledgeNode(config, t, vars, ctx);
                return null;
            }
            case "tool" -> {
                // P1/B4:接平台工具注册体系(http/sandbox/sql),结果写入节点输出变量
                executeToolNode(config, t, vars, ctx.agent().getWorkspaceId());
                return null;
            }
            case "end" -> {
                String output = interpolate(config.path("output").asText(""), vars);
                vars.put(t.getNodeName() + ".output", output);
                t.setOutput(output);
                t.setStatus("success");
                return null;
            }
            case "join" -> {
                // W7:并行分支的确定性汇聚点,分支结果已在 executeParallelNode 归并进上下文
                t.setStatus("success");
                t.setOutput("并行分支已汇合");
                return null;
            }
            case "condition" -> {
                String selected = executeConditionNode(config, t, vars);
                t.setStatus("success");
                t.setOutput("选中分支:" + selected);
                return selected;
            }
            case "approval" -> {
                // 人工审批节点:生成审批单并暂停运行,审批中心处理后恢复续跑
                if (ctx.run() == null) {
                    throw new ServiceException("审批节点缺少运行实例,无法暂停");
                }
                String instruction = interpolate(config.path("message").asText("请审批确认后继续执行"), vars);
                AgentApproval approval = new AgentApproval();
                approval.setSessionId(null);
                approval.setAgentId(ctx.agent().getId());
                approval.setRoundId(String.valueOf(ctx.run().getId()));
                approval.setToolName(t.getNodeName());
                approval.setToolSource("workflow");
                approval.setCallArgs(instruction);
                approval.setStatus("pending");
                // G1:审批截止时间,节点可配(分钟,默认 24h);过期清扫任务置 expired 并将 run 置 fail
                int expireMinutes = Math.max(1, Math.min(30 * 24 * 60, config.path("expireMinutes").asInt(24 * 60)));
                approval.setExpiredAt(new Date(System.currentTimeMillis() + expireMinutes * 60_000L));
                approvalMapper.insert(approval);
                throw new ApprovalPauseException(t.getNodeId(), t.getNodeName(), approval.getId());
            }
            default -> {
                String message = "未知节点类型[" + type + "],当前引擎不支持,试运行终止";
                // 体检 F2:节点状态统一为 fail(此前 default 分支写 failed,与全类不一致)
                t.setStatus("fail");
                t.setOutput(message);
                throw new ServiceException(message);
            }
        }
    }

    /**
     * 知识检索节点(P1 / B4):接 forge-rag 混合检索,命中内容作为节点输出变量。
     */
    private void executeKnowledgeNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t,
                                      Map<String, String> vars, WorkflowRunContext ctx) {
        Long kbId = parseLong(config.path("knowledgeId").asText(""));
        if (kbId == null) {
            throw new ServiceException("知识检索节点未配置知识库");
        }
        String query = interpolate(config.path("query").asText(""), vars);
        if (StringUtils.isBlank(query)) {
            query = vars.getOrDefault("start.query", "");
        }
        if (StringUtils.isBlank(query)) {
            throw new ServiceException("知识检索节点缺少查询内容(可在试运行入参中提供 start.query)");
        }
        KnowledgeSearchBo bo = new KnowledgeSearchBo();
        bo.setKnowledgeId(kbId);
        bo.setQuery(query.length() > 1000 ? query.substring(0, 1000) : query);
        bo.setTopK(Math.max(1, Math.min(20, config.path("topK").asInt(5))));
        bo.setSearchMode(StringUtils.defaultIfBlank(config.path("searchMode").asText(""), "hybrid"));
        if (ctx.fromTrigger()) {
            // 体检 S2:无登录上下文链路(触发器/webhook/对外API/聊天后台线程)按工作流归属空间校验知识库归属
            bo.setCallerWorkspaceId(ctx.agent().getWorkspaceId());
        }
        // 画布试运行保留登录态权限校验
        List<KnowledgeSearchHitVo> hits = ctx.fromTrigger()
            ? knowledgeService.searchInternal(bo) : knowledgeService.search(bo);
        StringBuilder text = new StringBuilder();
        if (hits != null) {
            for (KnowledgeSearchHitVo hit : hits) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append("[来源]").append(hit.getFileName() == null ? "" : hit.getFileName())
                    .append('\n').append(hit.getContent() == null ? "" : hit.getContent());
            }
        }
        String output = text.isEmpty() ? "(未命中相关内容)" : text.toString();
        vars.put(t.getNodeName() + ".output", output);
        t.setOutput(output);
        t.setStatus("success");
    }

    /**
     * 工具节点(P1 / B4):按 toolId 装配平台工具并真实执行,结果作为节点输出变量。
     */
    private void executeToolNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t,
                                 Map<String, String> vars, Long callerWorkspaceId) {
        Long toolId = parseLong(config.path("toolId").asText(""));
        if (toolId == null) {
            throw new ServiceException("工具节点未配置工具");
        }
        List<ToolAssembler.ToolDef> defs = contextAssembler.loadToolDefs(List.of(toolId), callerWorkspaceId);
        if (defs.isEmpty()) {
            throw new ServiceException("工具不存在或已停用:" + toolId);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        // W0:参数字段统一为 args;兼容修复前的历史画布数据(旧字段 paramMapping)
        JsonNode argsNode = config.path("args");
        String argsRaw = (argsNode.isMissingNode() || argsNode.isNull())
            ? config.path("paramMapping").asText("{}") : argsNode.asText("{}");
        String argsJson = interpolate(argsRaw, vars);
        try {
            Object parsed = objectMapper.readValue(argsJson, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> args.put(String.valueOf(k), v));
            }
        } catch (Exception e) {
            throw new ServiceException("工具节点参数不是合法 JSON 对象");
        }
        String output;
        try (ToolAssembler.Assembly assembly = toolAssembler.buildForWorkflow(defs)) {
            AgentTool tool = assembly.toolkit().getTool(defs.getFirst().name());
            if (tool == null) {
                throw new ServiceException("工具装配失败:" + defs.getFirst().name());
            }
            ToolResultBlock block = tool.callAsync(ToolCallParam.builder().input(args).build())
                .block(Duration.ofSeconds(60));
            output = toolResultText(block);
        }
        vars.put(t.getNodeName() + ".output", output);
        t.setOutput(output);
        t.setStatus("success");
    }

    private String toolResultText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null || block.getOutput().isEmpty()) {
            return "(工具无返回)";
        }
        StringBuilder text = new StringBuilder();
        for (Object item : block.getOutput()) {
            if (item instanceof TextBlock tb) {
                text.append(tb.getText());
            } else if (item != null) {
                text.append(item);
            }
        }
        return text.isEmpty() ? "(工具无文本返回)" : text.toString();
    }

    public static Long parseLong(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 条件节点:解析分支条件,返回第一个命中的分支名称;无命中则返回第一个分支
     */
    private String executeConditionNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t,
                                        Map<String, String> vars) {
        JsonNode branches = config.path("branches");
        if (!branches.isArray() || branches.isEmpty()) {
            return "default";
        }
        for (JsonNode branch : branches) {
            String name = branch.path("name").asText("");
            String condition = branch.path("condition").asText("");
            if (StringUtils.isBlank(condition)) {
                continue;
            }
            String resolved = interpolate(condition, vars).trim();
            Boolean hit = evalCondition(resolved);
            if (hit == null) {
                // W6:不支持的语法显式报错,不再静默落默认分支
                throw new ServiceException("条件节点[" + t.getNodeName() + "]分支[" + name
                    + "]表达式语法不支持:" + resolved + "(支持 == != contains not contains > >= < <=)");
            }
            if (hit) {
                return name;
            }
        }
        // 默认返回第一个分支
        return branches.path(0).path("name").asText("");
    }

    /**
     * W6:条件表达式求值已提取到 {@link WorkflowExpressionEngine},此处仅委托
     */
    static Boolean evalCondition(String resolved) {
        return WorkflowExpressionEngine.evalCondition(resolved);
    }

    /**
     * LLM 节点:绑定模型经 AgentScope ReActAgent 真实执行,输出注册为 <节点名>.output
     */
    private void executeLlmNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t,
                                Map<String, String> vars, AgentInfo agent, AgentWorkflowRun run) {
        long modelId = config.path("modelId").isNumber()
            ? config.path("modelId").asLong() : parseLongSafe(config.path("modelId").asText(null));
        if (modelId <= 0) {
            throw new ServiceException("LLM 节点[" + t.getNodeName() + "]未绑定模型");
        }
        AgentModel model = modelMapper.selectById(modelId);
        if (model != null) {
            resourceGuard.assertReadableBy(model.getWorkspaceId(), agent.getWorkspaceId());
        }
        if (model == null || StringUtils.isBlank(model.getApiKey())
            || StringUtils.isBlank(model.getBaseUrl())) {
            throw new ServiceException("LLM 节点[" + t.getNodeName() + "]绑定的模型接入信息不完整:"
                + (model == null ? "模型不存在" : model.getName()));
        }
        String prompt = interpolate(config.path("prompt").asText(""), vars);
        if (StringUtils.isBlank(prompt)) {
            throw new ServiceException("LLM 节点[" + t.getNodeName() + "]提示词为空");
        }
        Model asModel = AgentScopeModelFactory.build(
            model.getBaseUrl(), model.getApiKey(), model.getModelName(),
            config.path("temperature").isNumber() ? config.path("temperature").asDouble() : null,
            model.getMaxTokens());
        ReActAgent llmAgent = ReActAgent.builder()
            .name(t.getNodeName())
            .model(asModel)
            .maxIters(5)
            .build();
        Msg reply = llmAgent.call(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(prompt)
                .build())
            .block(Duration.ofSeconds(300));
        if (reply == null || StringUtils.isBlank(reply.getTextContent())) {
            throw new ServiceException("LLM 节点[" + t.getNodeName() + "]模型未返回有效内容");
        }
        String output = reply.getTextContent();
        vars.put(t.getNodeName() + ".output", output);
        t.setOutput(output);
        t.setStatus("success");
        recordTrialUsage(agent, model, prompt, output, reply.getChatUsage(),
            run == null ? null : run.getUserId());
    }

    /**
     * 循环节点:遍历迭代集合(JSON 数组/换行/逗号分隔),对每项以 {{item}} 插值后
     * 经 AgentScope ReAct 内核调用模型,结果按序拼接注册为 <节点名>.output
     */
    private void executeLoopNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t,
                                 Map<String, String> vars, AgentInfo agent, AgentWorkflowRun run) {
        long modelId = config.path("modelId").isNumber()
            ? config.path("modelId").asLong() : parseLongSafe(config.path("modelId").asText(null));
        if (modelId <= 0) {
            throw new ServiceException("循环节点[" + t.getNodeName() + "]未绑定模型");
        }
        AgentModel model = modelMapper.selectById(modelId);
        if (model != null) {
            resourceGuard.assertReadableBy(model.getWorkspaceId(), agent.getWorkspaceId());
        }
        if (model == null || StringUtils.isBlank(model.getApiKey())
            || StringUtils.isBlank(model.getBaseUrl())) {
            throw new ServiceException("循环节点[" + t.getNodeName() + "]绑定的模型接入信息不完整:"
                + (model == null ? "模型不存在" : model.getName()));
        }
        String itemsRaw = interpolate(config.path("items").asText(""), vars);
        List<String> items = parseLoopItems(itemsRaw);
        if (items.isEmpty()) {
            throw new ServiceException("循环节点[" + t.getNodeName() + "]迭代集合为空");
        }
        int maxLoops = config.path("maxLoops").isNumber() ? config.path("maxLoops").asInt(10) : 10;
        if (items.size() > maxLoops) {
            throw new ServiceException("循环节点[" + t.getNodeName() + "]迭代项数 " + items.size()
                + " 超过上限 " + maxLoops + ",请缩减集合或调大 maxLoops(最大 20)");
        }
        String promptTemplate = config.path("prompt").asText("");
        Model asModel = AgentScopeModelFactory.build(
            model.getBaseUrl(), model.getApiKey(), model.getModelName(),
            config.path("temperature").isNumber() ? config.path("temperature").asDouble() : null,
            model.getMaxTokens());
        ReActAgent loopAgent = ReActAgent.builder()
            .name(t.getNodeName())
            .model(asModel)
            .maxIters(5)
            .build();
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, String> itemVars = new LinkedHashMap<>(vars);
            itemVars.put("item", items.get(i));
            String prompt = interpolate(promptTemplate, itemVars);
            if (StringUtils.isBlank(prompt)) {
                throw new ServiceException("循环节点[" + t.getNodeName() + "]第 " + (i + 1) + " 项提示词为空");
            }
            Msg reply = loopAgent.call(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(prompt)
                    .build())
                .block(Duration.ofSeconds(300));
            if (reply == null || StringUtils.isBlank(reply.getTextContent())) {
                throw new ServiceException("循环节点[" + t.getNodeName() + "]第 " + (i + 1) + " 项模型未返回有效内容");
            }
            String output = reply.getTextContent();
            outputs.add(output);
            recordTrialUsage(agent, model, prompt, output, reply.getChatUsage(),
                run == null ? null : run.getUserId());
        }
        String joined = String.join("\n\n", outputs);
        vars.put(t.getNodeName() + ".output", joined);
        t.setOutput("循环 " + items.size() + " 项完成,输出 " + joined.length() + " 字符:\n" + joined);
        t.setStatus("success");
    }

    /**
     * 解析循环节点迭代集合:优先 JSON 数组(元素转字符串),否则按换行/逗号切分
     */
    private List<String> parseLoopItems(String raw) {
        List<String> items = new ArrayList<>();
        if (StringUtils.isBlank(raw)) {
            return items;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonNode arr = objectMapper.readTree(trimmed);
                if (arr.isArray()) {
                    arr.forEach(n -> items.add(n.isTextual() ? n.asText() : n.toString()));
                    return items;
                }
            } catch (Exception ignore) {
                // 非 JSON 数组回退文本切分
            }
        }
        for (String part : trimmed.split("\\r?\\n|,")) {
            if (!part.isBlank()) {
                items.add(part.trim());
            }
        }
        return items;
    }

    /**
     * 代码节点:在 Docker 沙箱中执行 Python 代码(无网络,内存/CPU/超时限额),
     * stdout/stderr 注册为 <节点名>.output;非零退出码视为执行失败
     */
    private void executeCodeNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t, Map<String, String> vars) {
        String code = config.path("code").asText("");
        Integer timeout = config.path("timeoutSeconds").isNumber() ? config.path("timeoutSeconds").asInt() : 30;
        String output = sandboxCodeExecutor.execute(new SandboxCodeExecutor.SandboxConfig(
            code, config.path("image").asText(null), timeout,
            config.path("memory").asText(null), config.path("cpus").asText(null)));
        if (output.contains("[退出码: ") && !output.contains("[退出码: 0]")) {
            throw new ServiceException("代码节点[" + t.getNodeName() + "]执行失败:" + output);
        }
        vars.put(t.getNodeName() + ".output", output);
        t.setOutput(output);
        t.setStatus("success");
    }

    /**
     * HTTP 请求节点:执行一次真实 HTTP 调用,响应体注册为 <节点名>.output;
     * 非 2xx 状态码视为执行失败
     */
    private void executeHttpNode(JsonNode config, WorkflowTrialRunVo.NodeTrace t, Map<String, String> vars) {
        String method = config.path("method").asText("GET").trim().toUpperCase();
        if (!HTTP_METHODS.contains(method)) {
            throw new ServiceException("HTTP 节点[" + t.getNodeName() + "]请求方法不支持:" + method);
        }
        String url = interpolate(config.path("url").asText(""), vars).trim();
        if (StringUtils.isBlank(url)) {
            throw new ServiceException("HTTP 节点[" + t.getNodeName() + "]请求地址为空");
        }
        // 体检 S3:SSRF 防护——URL 参与变量插值,外部输入(webhook/对话/API)可改写目标地址
        guardHttpUrl(url, t.getNodeName());
        int timeout = config.path("timeoutSeconds").isNumber()
            ? Math.max(1, config.path("timeoutSeconds").asInt()) : 30;
        JsonNode headers = parseJson(config.path("headers").asText("{}"));
        String body = interpolate(config.path("body").asText(""), vars);
        HttpResponse<String> response;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeout, 15)))
                // 体检 S3:禁用重定向,防止 302 跳转绕过目标地址校验
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout));
            headers.fieldNames().forEachRemaining(h -> builder.header(h, headers.path(h).asText("")));
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else {
                if (!headers.has("Content-Type") && !headers.has("content-type") && StringUtils.isNotBlank(body)) {
                    builder.header("Content-Type", "application/json; charset=utf-8");
                }
                builder.method(method, StringUtils.isBlank(body)
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            }
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ServiceException("HTTP 节点[" + t.getNodeName() + "]请求失败:"
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        String bodyText = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("HTTP 节点[" + t.getNodeName() + "]响应状态码 "
                + response.statusCode() + ":" + WorkflowTraceCodec.truncateForTrace(bodyText, 500));
        }
        vars.put(t.getNodeName() + ".output", bodyText);
        t.setOutput("HTTP " + response.statusCode() + ",响应 " + bodyText.length() + " 字符:\n"
            + WorkflowTraceCodec.truncateForTrace(bodyText, 2000));
        t.setStatus("success");
    }

    /**
     * 体检 S3:工作流 http 节点的 SSRF 守护。
     * 仅允许 http/https;DNS 解析后拒绝回环/私网/链路本地/通配地址——
     * URL 参与变量插值,webhook、对外 API、对话输入都可能改写目标地址打内网。
     * 已知纵深缺口(记录):DNS rebinding(校验与请求两次解析)与 302 后的二次解析
     * 已通过禁用重定向消除;rebinding 需连接级 pin IP,待出现真实需求再评估。
     */
    private void guardHttpUrl(String url, String nodeName) {
        if (!runtimeProperties.isWorkflowHttpGuardEnabled()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new ServiceException("HTTP 节点[" + nodeName + "]请求地址非法:" + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException("HTTP 节点[" + nodeName + "]仅允许 http/https 协议");
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new ServiceException("HTTP 节点[" + nodeName + "]请求地址缺少主机名");
        }
        java.net.InetAddress address;
        try {
            address = java.net.InetAddress.getByName(host);
        } catch (Exception e) {
            throw new ServiceException("HTTP 节点[" + nodeName + "]目标主机无法解析:" + host);
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw new ServiceException("HTTP 节点[" + nodeName + "]禁止访问内网或保留地址");
        }
    }

    /**
     * 记录试运行/触发器运行 LLM 节点用量(source=trial,无会话归属)
     */
    private void recordTrialUsage(AgentInfo agent, AgentModel model,
                                  String prompt, String output, ChatUsage usage, Long operatorUserId) {
        try {
            AgentTokenUsage u = new AgentTokenUsage();
            u.setWorkspaceId(agent.getWorkspaceId());
            u.setAgentId(agent.getId());
            u.setSessionId(null);
            u.setRoundRequestId(UUID.randomUUID().toString().replace("-", ""));
            u.setUserId(operatorUserId);
            u.setSource("trial");
            u.setModelId(model.getId());
            u.setModelName(model.getModelName());
            if (usage != null) {
                u.setPromptTokens(usage.getInputTokens());
                u.setCompletionTokens(usage.getOutputTokens());
                u.setTotalTokens(usage.getTotalTokens());
                u.setEstimated("0");
            } else {
                int p = Math.max(1, prompt.length() / 4);
                int c = Math.max(1, output.length() / 4);
                u.setPromptTokens(p);
                u.setCompletionTokens(c);
                u.setTotalTokens(p + c);
                u.setEstimated("1");
            }
            u.setSuccess("0");
            u.setCallTime(LocalDateTime.now());
            tokenUsageRecorder.record(u);
        } catch (Exception ignore) {
            // 用量采集失败不影响试运行
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new ServiceException("JSON 解析失败:" + e.getMessage());
        }
    }

    private long parseLongSafe(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
