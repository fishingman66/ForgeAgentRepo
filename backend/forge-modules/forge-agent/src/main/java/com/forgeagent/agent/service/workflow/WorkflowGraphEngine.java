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
import com.forgeagent.agent.service.workflow.WorkflowNodeExecutors;
import com.forgeagent.agent.service.workflow.run.ApprovalPauseException;
import com.forgeagent.agent.service.workflow.run.WorkflowRunContext;
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
import com.forgeagent.agent.service.workflow.run.ExecutionOutcome;
import com.forgeagent.agent.service.workflow.run.WorkflowRunContext;
import org.springframework.stereotype.Component;

/**
 * 工作流图编排引擎:拓扑调度、条件选路、并行分支、节点策略(重试/超时/失败跳转)与事件推送。
 *
 * <p>从 {@code AgentWorkflowServiceImpl} 整体抽出,编排语义与原代码逐字一致。
 * 节点的具体执行交给 {@link WorkflowNodeExecutors},运行实例的落库与生命周期
 * 仍由 Service 负责,本类不碰任何 Mapper。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class WorkflowGraphEngine {

    private final WorkflowNodeExecutors nodeExecutors;
    private final RunEventBuffer eventBuffer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** W8:dryRun 桩输出覆盖的外部调用节点类型(节点级超时不覆盖 knowledge,避免脱离登录态线程) */
    private static final Set<String> EXTERNAL_NODE_TYPES = Set.of("llm", "tool", "http", "loop", "code", "knowledge");

    /** W7:并行分支执行池(固定有界,CallerRuns 兜底防拒绝) */
    private static final ExecutorService WORKFLOW_EXECUTOR = new ThreadPoolExecutor(
        4, 8, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
        r -> {
            Thread t = new Thread(r, "wf-branch");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());

    /** W5:节点级超时执行池(虚拟线程,LLM/HTTP 等阻塞调用安全) */
    private static final ExecutorService NODE_TIMEOUT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** W9:工作流事件统一出口,写入失败只留日志不阻断执行 */
    public void publishEvent(Long runId, String event, Map<?, ?> payload) {
        if (runId == null) {
            return;
        }
        try {
            eventBuffer.publish("wf-" + runId, event,
                com.forgeagent.common.json.utils.JsonUtils.toJsonString(payload));
        } catch (Exception ignore) {
            // 事件写入失败不影响主链路
        }
    }

    /** W7:出边信息(branchName 为条件分支名,W6 起条件出边按分支名关联,空名兼容旧数据走下标) */
    /** 图算法委托 {@link WorkflowGraphValidator#bfs} */
    private Set<String> bfs(String start, Map<String, List<String>> adj) {
        return WorkflowGraphValidator.bfs(start, adj);
    }

    /** W5:节点执行结果(branch 仅条件节点返回;gotoTarget 非空表示失败跳转) */
    private record NodeResult(String branch, String gotoTarget) {
    }

    /** W7:并行分支执行结果(分支内变量与轨迹隔离,由主线程归并) */
    private record BranchResult(boolean success, String error, Map<String, String> vars,
                                List<WorkflowTrialRunVo.NodeTrace> trace, List<String> executed, String lastOutput) {
    }

    /** W5:节点级超时(禁止重试与失败跳转,防对已产生的外部副作用重复触发) */
    private static class NodeTimeoutException extends RuntimeException {
        NodeTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * 按拓扑序执行工作流,执行轨迹实时写入 trace
     *
     * <p>支持恢复语义:completed/skipped 非空时,这些节点的出边视为已触发,
     * 恢复执行从入度清零的未执行节点继续(用于人工审批后的续跑)。</p>
     *
     * <p>W5:节点失败走 failStrategy(terminate/continue/goto)与重试;W6:条件出边按
     * branchName 关联;W7:parallel 节点分支并发执行后确定性 JOIN;W8:dryRun/
     * startNodeKey/maxSteps。</p>
     *
     * @return success=全部节点执行完成;pendingNode 非空=触发人工审批暂停
     */
    public ExecutionOutcome executeGraph(String workflowJson, String inputs, AgentInfo agent,
                                          List<WorkflowTrialRunVo.NodeTrace> trace,
                                          Map<String, String> vars, Set<String> completed,
                                          Set<String> skipped, AgentWorkflowRun run, boolean fromTrigger,
                                          boolean dryRun, String startNodeKey, int maxSteps) {
        JsonNode root;
        try {
            root = objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            throw new ServiceException("工作流 JSON 解析失败:" + e.getMessage());
        }
        Map<String, JsonNode> nodeMap = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, List<OutEdge>> adjOut = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (JsonNode node : root.path("nodes")) {
            String id = node.path("id").asText("");
            nodeMap.put(id, node);
            inDegree.put(id, 0);
        }
        for (JsonNode edge : root.path("edges")) {
            String source = edge.path("source").asText("");
            String target = edge.path("target").asText("");
            if (nodeMap.containsKey(source) && nodeMap.containsKey(target)) {
                adj.computeIfAbsent(source, k -> new ArrayList<>()).add(target);
                adjOut.computeIfAbsent(source, k -> new ArrayList<>())
                    .add(new OutEdge(target, edge.path("branchName").asText("")));
                inDegree.merge(target, 1, Integer::sum);
            }
        }
        WorkflowRunContext ctx = new WorkflowRunContext(agent, run, fromTrigger, dryRun, maxSteps, new AtomicInteger(0), inputs);
        // W8:指定起跑节点,其上游节点视为已完成
        if (StringUtils.isNotBlank(startNodeKey)) {
            if (!nodeMap.containsKey(startNodeKey)) {
                throw new ServiceException("起跑节点不存在:" + startNodeKey);
            }
            Set<String> reach = bfs(startNodeKey, adj);
            for (String nid : nodeMap.keySet()) {
                if (!reach.contains(nid)) {
                    completed.add(nid);
                }
            }
        }
        // 恢复模式:已完成/已跳过节点的出边视为已触发
        for (String done : completed) {
            for (String next : adj.getOrDefault(done, new ArrayList<>())) {
                inDegree.merge(next, -1, Integer::sum);
            }
        }
        for (String done : skipped) {
            for (String next : adj.getOrDefault(done, new ArrayList<>())) {
                inDegree.merge(next, -1, Integer::sum);
            }
        }
        // 变量上下文:start.<key> 来自试运行输入,llm/end 节点注册 <节点名>.output
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.offer(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (completed.contains(cur) || skipped.contains(cur)) {
                // 恢复模式下已处理过的节点不重复执行(出边已减)
                continue;
            }
            if (ctx.steps().incrementAndGet() > ctx.maxSteps()) {
                throw new ServiceException("工作流执行步数超过上限 " + ctx.maxSteps()
                    + ",疑似条件环失控,已终止(可通过 maxSteps 调整,上限 500)");
            }
            JsonNode node = nodeMap.get(cur);
            WorkflowTrialRunVo.NodeTrace t = newTrace(node, cur);
            trace.add(t);
            String type = node.path("type").asText("");
            publishEvent(run.getId(), "node_start", Map.of("nodeId", cur,
                "nodeName", t.getNodeName(), "nodeType", type));
            try {
                // W7:并行节点分支并发执行,汇聚后由 JOIN 节点继续
                if ("parallel".equals(type)) {
                    executeParallelNode(node, t, ctx, trace, vars, completed, nodeMap, adjOut, adj, inDegree, queue);
                    continue;
                }
                NodeResult result = runNodeWithPolicy(node, t, vars, ctx);
                completed.add(cur);
                publishEvent(run.getId(), "node_done", Map.of("nodeId", cur,
                    "nodeName", t.getNodeName(), "nodeType", type,
                    "status", t.getStatus(),
                    "output", WorkflowTraceCodec.truncateForTrace(t.getOutput(), 2000)));
                if ("skipped".equals(t.getStatus())) {
                    // 被跳过的节点:不执行实际逻辑,出边正常减入度
                    skipped.add(cur);
                    decrementOut(adj, inDegree, queue, cur);
                    continue;
                }
                if (result.gotoTarget() != null) {
                    // W5 GOTO:强制跳转到目标节点(入度置负防重复入队;maxSteps 兜底防环)
                    if (!nodeMap.containsKey(result.gotoTarget())) {
                        throw new ServiceException("失败跳转目标节点不存在:" + result.gotoTarget());
                    }
                    inDegree.put(result.gotoTarget(), -1);
                    queue.offer(result.gotoTarget());
                    continue;
                }
                if ("condition".equals(type) && result.branch() != null) {
                    selectConditionBranches(node, cur, result.branch(), nodeMap, adjOut, adj, inDegree, queue, trace, skipped);
                    continue;
                }
                decrementOut(adj, inDegree, queue, cur);
            } catch (ApprovalPauseException pause) {
                t.setStatus("pending");
                t.setOutput("等待人工审批(审批单 " + pause.approvalId + "),审批通过后自动续跑");
                return new ExecutionOutcome(false, pause.nodeId, pause.nodeName, pause.approvalId);
            } catch (Exception e) {
                t.setStatus("fail");
                t.setOutput(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                publishEvent(run.getId(), "node_error", Map.of("nodeId", cur,
                    "nodeName", t.getNodeName(), "nodeType", type, "error", t.getOutput()));
                return new ExecutionOutcome(false, null, null, null);
            }
        }
        return new ExecutionOutcome(true, null, null, null);
    }

    private WorkflowTrialRunVo.NodeTrace newTrace(JsonNode node, String nodeId) {
        WorkflowTrialRunVo.NodeTrace t = new WorkflowTrialRunVo.NodeTrace();
        t.setNodeId(nodeId);
        t.setNodeName(node.path("name").asText(""));
        t.setNodeType(node.path("type").asText(""));
        return t;
    }

    private void decrementOut(Map<String, List<String>> adj, Map<String, Integer> inDegree,
                              Deque<String> queue, String cur) {
        for (String next : adj.getOrDefault(cur, new ArrayList<>())) {
            if (inDegree.merge(next, -1, Integer::sum) == 0) {
                queue.offer(next);
            }
        }
    }

    /**
     * W6:条件节点选路 —— 出边优先按 branchName 关联(前端连线时写入),
     * 全部无标注时兼容旧数据按下标对齐
     */
    private void selectConditionBranches(JsonNode node, String nodeId, String selectedBranch,
                                         Map<String, JsonNode> nodeMap, Map<String, List<OutEdge>> adjOut,
                                         Map<String, List<String>> adj, Map<String, Integer> inDegree,
                                         Deque<String> queue,
                                         List<WorkflowTrialRunVo.NodeTrace> trace, Set<String> skipped) {
        List<OutEdge> outs = adjOut.getOrDefault(nodeId, new ArrayList<>());
        JsonNode branches = node.path("config").path("branches");
        boolean anyNamed = outs.stream().anyMatch(e -> StringUtils.isNotBlank(e.branchName()));
        for (int i = 0; i < outs.size(); i++) {
            OutEdge edge = outs.get(i);
            String branchName = anyNamed ? edge.branchName() : branches.path(i).path("name").asText("");
            if (selectedBranch.equals(branchName)) {
                if (inDegree.merge(edge.target(), -1, Integer::sum) == 0) {
                    queue.offer(edge.target());
                }
            } else {
                skipBranch(edge.target(), nodeMap, adj, inDegree, queue, trace, skipped);
            }
        }
    }

    /**
     * 跳过条件分支未选中的路径:递归标记下游节点为 skipped 并处理入度
     */
    private void skipBranch(String nodeId, Map<String, JsonNode> nodeMap,
                            Map<String, List<String>> adj, Map<String, Integer> inDegree,
                            Deque<String> queue, List<WorkflowTrialRunVo.NodeTrace> trace,
                            Set<String> skipped) {
        JsonNode node = nodeMap.get(nodeId);
        if (node == null || skipped.contains(nodeId)) {
            return;
        }
        WorkflowTrialRunVo.NodeTrace t = new WorkflowTrialRunVo.NodeTrace();
        t.setNodeId(nodeId);
        t.setNodeName(node.path("name").asText(""));
        t.setNodeType(node.path("type").asText(""));
        t.setStatus("skipped");
        t.setOutput("条件分支未选中");
        trace.add(t);
        skipped.add(nodeId);
        // 减入度并处理下游:入度全部来自被跳过分支的节点递归跳过,而非入队执行
        for (String next : adj.getOrDefault(nodeId, new ArrayList<>())) {
            if (inDegree.merge(next, -1, Integer::sum) == 0) {
                skipBranch(next, nodeMap, adj, inDegree, queue, trace, skipped);
            }
        }
    }

    /**
     * W5/W8:节点执行统一入口 —— dryRun 桩输出、重试、节点级超时、失败策略(terminate/continue/goto)。
     * 超时的节点禁止重试与失败跳转,避免对可能已产生的外部副作用重复触发。
     */
    private NodeResult runNodeWithPolicy(JsonNode node, WorkflowTrialRunVo.NodeTrace t,
                                         Map<String, String> vars, WorkflowRunContext ctx) {
        String type = node.path("type").asText("");
        JsonNode config = node.path("config");
        boolean external = EXTERNAL_NODE_TYPES.contains(type);
        if (ctx.dryRun() && external) {
            String stub = "(dryRun 桩输出:" + type + ")";
            vars.put(t.getNodeName() + ".output", stub);
            t.setOutput(stub);
            t.setStatus("success");
            return new NodeResult(null, null);
        }
        int retries = Math.max(0, Math.min(3, config.path("retryCount").asInt(0)));
        long intervalMs = Math.max(0, Math.min(5000, config.path("retryIntervalMs").asLong(0)));
        long timeoutMs = config.path("timeoutMs").asLong(0);
        RuntimeException last = null;
        boolean timedOut = false;
        String selected = null;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            try {
                if (timeoutMs > 0 && external && !"knowledge".equals(type)) {
                    runNodeWithTimeout(node, t, vars, ctx, timeoutMs);
                } else {
                    selected = nodeExecutors.executeNode(node, t, vars, ctx);
                }
                return new NodeResult(selected, null);
            } catch (ApprovalPauseException p) {
                throw p;
            } catch (NodeTimeoutException nte) {
                timedOut = true;
                last = nte;
                break;
            } catch (RuntimeException e) {
                last = e;
                if (attempt <= retries && intervalMs > 0) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ServiceException("节点重试等待被中断");
                    }
                }
            }
        }
        String msg = last == null || last.getMessage() == null ? "未知错误" : last.getMessage();
        // 超时禁止 GOTO(重试已同步禁止);continue 兜底仍可用
        String strategy = timedOut ? "terminate" : config.path("failStrategy").asText("terminate");
        if ("goto".equals(strategy)) {
            String target = config.path("failureTargetNodeKey").asText("");
            t.setStatus("failed_goto");
            t.setOutput("节点失败,按策略跳转至 [" + target + "]:" + msg);
            vars.put(t.getNodeName() + ".output", "(失败跳转)" + msg);
            return new NodeResult(null, target);
        }
        if ("continue".equals(strategy)) {
            String fallback = interpolate(config.path("fallbackOutput").asText(""), vars);
            String output = StringUtils.isBlank(fallback) ? "节点失败(按策略继续):" + msg : fallback;
            vars.put(t.getNodeName() + ".output", output);
            t.setStatus("failed_continue");
            t.setOutput(output);
            return new NodeResult(null, null);
        }
        t.setStatus("fail");
        t.setOutput((retries > 0 && !timedOut ? "重试 " + retries + " 次后失败:" : "") + msg);
        throw last != null ? last : new ServiceException(msg);
    }

    /**
     * W5:节点级超时包装(独立线程池执行,future.cancel 中断阻塞调用)。
     * knowledge 节点不包装,避免脱离登录态线程导致 Sa-Token 权限校验失败。
     */
    private void runNodeWithTimeout(JsonNode node, WorkflowTrialRunVo.NodeTrace t,
                                    Map<String, String> vars, WorkflowRunContext ctx, long timeoutMs) {
        Future<?> future = NODE_TIMEOUT_EXECUTOR.submit(() -> nodeExecutors.executeNode(node, t, vars, ctx));
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new NodeTimeoutException("节点[" + t.getNodeName() + "]执行超时(" + timeoutMs + "ms),已中断");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ServiceException("节点执行被中断");
        }
    }

    /**
     * W7:并行节点 —— 出边即分支起点,并发执行各分支至共同 JOIN 节点后确定性归并。
     * 语义参照 OAF 引擎:分支上下文隔离、按分支起点序合并(putIfAbsent 基础上下文优先)、
     * 分支内禁止嵌套并行/人工审批/条件分支(校验层同步拦截)。
     */
    private void executeParallelNode(JsonNode node, WorkflowTrialRunVo.NodeTrace t, WorkflowRunContext ctx,
                                     List<WorkflowTrialRunVo.NodeTrace> trace, Map<String, String> vars,
                                     Set<String> completed, Map<String, JsonNode> nodeMap,
                                     Map<String, List<OutEdge>> adjOut, Map<String, List<String>> adj,
                                     Map<String, Integer> inDegree, Deque<String> queue) {
        String nodeId = node.path("id").asText("");
        List<OutEdge> outs = adjOut.getOrDefault(nodeId, new ArrayList<>());
        if (outs.size() < 2) {
            throw new ServiceException("并行节点[" + t.getNodeName() + "]至少需要两条出边分支");
        }
        // BFS 求各分支可达集合的交集,必须恰好汇聚到一个 JOIN 节点
        Set<String> common = null;
        for (OutEdge edge : outs) {
            Set<String> reach = bfs(edge.target(), adj);
            common = common == null ? new HashSet<>(reach) : common;
            common.retainAll(reach);
        }
        List<String> joins = (common == null ? Set.<String>of() : common).stream()
            .filter(id -> "join".equals(nodeMap.get(id).path("type").asText("")))
            .sorted().toList();
        if (joins.isEmpty()) {
            throw new ServiceException("并行节点[" + t.getNodeName() + "]的分支未汇聚到任何 JOIN 节点");
        }
        if (joins.size() > 1) {
            throw new ServiceException("并行节点[" + t.getNodeName() + "]的分支汇聚到多个 JOIN 节点,请改为单汇入");
        }
        String joinId = joins.get(0);
        String joinName = nodeMap.get(joinId).path("name").asText("");
        List<CompletableFuture<BranchResult>> futures = new ArrayList<>();
        for (OutEdge edge : outs) {
            futures.add(CompletableFuture.supplyAsync(
                () -> runBranch(edge.target(), joinId, ctx, nodeMap, adjOut, vars), WORKFLOW_EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        Map<String, String> summary = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            OutEdge edge = outs.get(i);
            BranchResult br = futures.get(i).join();
            trace.addAll(br.trace());
            if (!br.success()) {
                t.setStatus("fail");
                t.setOutput("并行分支 " + (i + 1) + " 执行失败:" + br.error());
                publishEvent(ctx.run().getId(), "node_error", Map.of("nodeId", nodeId,
                    "nodeName", t.getNodeName(), "nodeType", "parallel", "error", t.getOutput()));
                throw new ServiceException("并行分支 " + (i + 1) + " 执行失败:" + br.error());
            }
            completed.addAll(br.executed());
            // 按分支起点序归并:基础上下文优先,分支新增字段 putIfAbsent
            for (Map.Entry<String, String> e : br.vars().entrySet()) {
                vars.putIfAbsent(e.getKey(), e.getValue());
            }
            String startName = nodeMap.get(edge.target()).path("name").asText("分支" + (i + 1));
            summary.put(startName, br.lastOutput() == null ? "" : br.lastOutput());
        }
        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            summaryJson = "{}";
        }
        vars.put(joinName + ".output", summaryJson);
        t.setStatus("success");
        t.setOutput(outs.size() + " 条分支并行完成,汇总结果注册为 " + joinName + ".output");
        publishEvent(ctx.run().getId(), "node_done", Map.of("nodeId", nodeId,
            "nodeName", t.getNodeName(), "nodeType", "parallel", "status", "success",
            "output", WorkflowTraceCodec.truncateForTrace(t.getOutput(), 2000)));
        completed.add(nodeId);
        // JOIN 节点强制入队(分支内部节点已手动执行并标记 completed,不走常规入度)
        inDegree.put(joinId, -1);
        queue.offer(joinId);
    }

    private BranchResult branchFail(String error) {
        return new BranchResult(false, error, new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(), null);
    }

    /**
     * W7:执行一条并行分支,顺序走到 JOIN 节点为止;分支变量写入独立 Map,由主线程归并
     */
    private BranchResult runBranch(String startId, String joinId, WorkflowRunContext ctx,
                                   Map<String, JsonNode> nodeMap, Map<String, List<OutEdge>> adjOut,
                                   Map<String, String> sharedVars) {
        List<WorkflowTrialRunVo.NodeTrace> branchTrace = new ArrayList<>();
        Map<String, String> branchVars = new LinkedHashMap<>(sharedVars);
        List<String> executed = new ArrayList<>();
        String cur = startId;
        int guard = 0;
        String lastOutput = null;
        while (cur != null && !joinId.equals(cur)) {
            if (++guard > ctx.maxSteps()) {
                return branchFail("分支执行步数超过上限 " + ctx.maxSteps());
            }
            JsonNode node = nodeMap.get(cur);
            if (node == null) {
                return branchFail("分支节点不存在:" + cur);
            }
            String name = node.path("name").asText("");
            String type = node.path("type").asText("");
            if ("parallel".equals(type)) {
                return branchFail("并行分支内不支持嵌套并行节点[" + name + "]");
            }
            if ("approval".equals(type)) {
                return branchFail("并行分支内不支持人工审批节点[" + name + "]");
            }
            if ("condition".equals(type)) {
                return branchFail("并行分支内不支持条件分支节点[" + name + "]");
            }
            WorkflowTrialRunVo.NodeTrace t = newTrace(node, cur);
            branchTrace.add(t);
            try {
                NodeResult r = runNodeWithPolicy(node, t, branchVars, ctx);
                if (r.gotoTarget() != null) {
                    return branchFail("并行分支内不支持失败跳转策略");
                }
                executed.add(cur);
                lastOutput = t.getOutput();
                List<OutEdge> outs = adjOut.getOrDefault(cur, new ArrayList<>());
                if (outs.size() > 1) {
                    return branchFail("分支内节点[" + name + "]存在多条出边");
                }
                if (outs.isEmpty()) {
                    return branchFail("并行分支在节点[" + name + "]处中断,未到达 JOIN 节点");
                }
                cur = outs.get(0).target();
            } catch (ApprovalPauseException p) {
                return branchFail("并行分支内触发人工审批");
            } catch (RuntimeException e) {
                return branchFail("节点[" + t.getNodeName() + "]:"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new BranchResult(true, null, branchVars, branchTrace, executed, lastOutput);
    }

}
