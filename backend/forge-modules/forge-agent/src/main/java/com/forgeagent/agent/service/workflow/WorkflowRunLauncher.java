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
import com.forgeagent.agent.service.workflow.WorkflowGraphEngine;
import com.forgeagent.agent.service.workflow.run.ExecutionOutcome;
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
import org.springframework.stereotype.Component;

/**
 * 工作流运行生命周期:触发、幂等、建运行实例、异步执行、执行快照、审批暂停与续跑、SSE 事件流。
 *
 * <p>从 {@code AgentWorkflowServiceImpl} 整体抽出,语义与原代码逐字一致。
 * 门面只保留定义/版本/查询与定时清扫;图执行委托 {@link WorkflowGraphEngine}。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class WorkflowRunLauncher {

    private final AgentInfoMapper agentMapper;
    private final AgentApprovalMapper approvalMapper;
    private final AgentWorkflowRunMapper runMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final RunEventBuffer eventBuffer;
    private final WorkflowGraphEngine graphEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowTraceCodec traceCodec = new WorkflowTraceCodec(objectMapper);
    private final WorkflowGraphValidator graphValidator = new WorkflowGraphValidator(new ObjectMapper());

    /** W9:异步试运行执行池(先落库返回 runId,后台执行) */
    private static final ExecutorService ASYNC_RUN_EXECUTOR = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
        r -> {
            Thread t = new Thread(r, "wf-async-run");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());


    public WorkflowTrialRunVo trialRun(WorkflowTrialRunBo bo) {
        AgentInfo agent = agentMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        return doTrialRun(agent, bo.getWorkflowJson(), bo.getInputs(), LoginHelper.getUserId(), false,
            Boolean.TRUE.equals(bo.getDryRun()), bo.getStartNodeKey(), bo.getMaxSteps(), null);
    }

    public Long trialRunAsync(WorkflowTrialRunBo bo) {
        AgentInfo agent = agentMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        // 与同步链路一致,提交前先做静态校验,拦截结构性问题
        WorkflowValidateVo validate = validateWorkflow(bo.getWorkflowJson());
        if (!Boolean.TRUE.equals(validate.getValid())) {
            throw new ServiceException("工作流校验未通过,无法执行:" + (validate.getBlockers().isEmpty()
                ? "未知错误" : validate.getBlockers().get(0).getMessage()));
        }
        return startRunInternal(agent, bo.getWorkflowJson(), bo.getInputs(), LoginHelper.getUserId(),
            Boolean.TRUE.equals(bo.getDryRun()), bo.getStartNodeKey(), bo.getMaxSteps());
    }

    public Long startRunForChat(Long agentId, String workflowJson, String inputs, Long userId) {
        AgentInfo agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        // W10:聊天链路已完成会话归属校验,这里不再重复空间权限校验;
        // 静态校验保留,工作流结构问题时直接抛错由聊天侧降级为错误回复
        WorkflowValidateVo validate = validateWorkflow(workflowJson);
        if (!Boolean.TRUE.equals(validate.getValid())) {
            throw new ServiceException("工作流校验未通过:" + (validate.getBlockers().isEmpty()
                ? "未知错误" : validate.getBlockers().get(0).getMessage()));
        }
        return startRunInternal(agent, workflowJson, inputs, userId, false, null, null);
    }

    public WorkflowTrialRunVo runResultInternal(Long runId) {
        AgentWorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ServiceException("工作流运行实例不存在");
        }
        WorkflowTrialRunVo vo = new WorkflowTrialRunVo();
        vo.setRunId(run.getId());
        vo.setStatus(run.getStatus());
        vo.setInputs(run.getInputs());
        vo.setTrace(StringUtils.isBlank(run.getTraceJson())
            ? new ArrayList<>() : traceCodec.readTrace(run.getTraceJson()));
        // message 取轨迹末条输出,供聊天侧在无 end 输出时兜底展示
        if (!vo.getTrace().isEmpty()) {
            vo.setMessage(vo.getTrace().get(vo.getTrace().size() - 1).getOutput());
        }
        return vo;
    }

    /**
     * W9/W10 共用:先落运行实例拿到 runId 立即返回,后台线程执行,进度走 SSE 事件。
     * 后台线程无登录上下文,knowledge 节点固定走内部检索入口(fromTrigger 语义)。
     */
    private Long startRunInternal(AgentInfo agent, String workflowJson, String inputs, Long userId,
                                  boolean dryRun, String startNodeKey, Integer maxSteps) {
        AgentWorkflowRun run = createRunRow(agent, workflowJson, inputs, userId, null);
        ASYNC_RUN_EXECUTOR.submit(() -> {
            try {
                doExecuteRun(agent, run, workflowJson, run.getInputs(), true,
                    dryRun, startNodeKey, maxSteps);
            } catch (Exception e) {
                // 后台执行兜底:确保运行实例落终态、事件流能收口
                try {
                    AgentWorkflowRun stale = runMapper.selectById(run.getId());
                    if (stale != null && "running".equals(stale.getStatus())) {
                        WorkflowTrialRunVo.NodeTrace t = new WorkflowTrialRunVo.NodeTrace();
                        t.setNodeId("-");
                        t.setNodeName("执行异常");
                        t.setNodeType("system");
                        t.setStatus("fail");
                        t.setOutput(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        snapshotRun(stale, "fail", null, null, new LinkedHashMap<>(),
                            new ArrayList<>(List.of(t)), new LinkedHashSet<>(), new LinkedHashSet<>());
                    }
                } catch (Exception ignore) {
                    // 快照兜底失败只留日志
                }
                graphEngine.publishEvent(run.getId(), "node_error",
                    Map.of("nodeId", "-", "nodeName", "执行异常", "nodeType", "system",
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                graphEngine.publishEvent(run.getId(), "done", Map.of("status", "fail", "message", "后台执行异常:" + e.getMessage()));
                eventBuffer.terminal("wf-" + run.getId(), "error");
            }
        });
        return run.getId();
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter runEvents(Long runId, Long after) {
        AgentWorkflowRun run = requireRun(runId);
        AgentInfo agent = agentMapper.selectById(run.getAgentId());
        if (agent != null) {
            permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        }
        String key = "wf-" + runId;
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(600_000L);
        long cursor = after == null ? 0 : after;
        Thread poller = new Thread(() -> {
            long pos = cursor;
            try {
                // 首帧固定下发 run_id,前端据此持有断点游标
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name("run_id").data(JsonUtils.toJsonString(Map.of("runId", run.getId()))));
                while (!Thread.currentThread().isInterrupted()) {
                    for (RunEventBuffer.RunEvent ev : eventBuffer.replay(key, pos)) {
                        pos = ev.sequence();
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .id(String.valueOf(ev.sequence())).name(ev.name()).data(oneLine(ev.data())));
                    }
                    String terminal = eventBuffer.terminalOf(key);
                    if (terminal != null) {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("terminal").data(JsonUtils.toJsonString(Map.of("status", terminal))));
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(400);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 客户端断开或发送失败:静默收口,重放连接可随时重建
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 已完成/已超时
                }
                return;
            }
        }, "wf-run-events-" + runId);
        poller.setDaemon(true);
        emitter.onCompletion(poller::interrupt);
        emitter.onTimeout(poller::interrupt);
        emitter.onError(e -> poller.interrupt());
        poller.start();
        return emitter;
    }

    /** SSE data 行不能含裸换行,JSON 内以转义序列承载 */
    private static String oneLine(String payload) {
        return payload == null ? "" : payload.replace("\r", "\\r").replace("\n", "\\n");
    }

    public WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId) {
        return triggerRun(agentId, inputs, operatorUserId, null);
    }

    public WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId, String idempotencyKey) {
        // 触发器执行无登录上下文,以触发器归属人作为运行身份,不做空间权限校验
        AgentInfo agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException("目标 Agent 不存在");
        }
        if (StringUtils.isBlank(agent.getWorkflowJson())) {
            throw new ServiceException("目标 Agent 未配置工作流");
        }
        // G3:幂等查重,终态复用结果,执行中去重
        if (StringUtils.isNotBlank(idempotencyKey)) {
            WorkflowTrialRunVo existing = findIdempotentRun(agent.getId(), idempotencyKey);
            if (existing != null) {
                return existing;
            }
        }
        try {
            return doTrialRun(agent, agent.getWorkflowJson(), inputs, operatorUserId, true, false, null, null,
                idempotencyKey);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发投递撞唯一索引:复用先到者的结果
            if (StringUtils.isNotBlank(idempotencyKey)) {
                WorkflowTrialRunVo existing = findIdempotentRun(agent.getId(), idempotencyKey);
                if (existing != null) {
                    return existing;
                }
            }
            throw e;
        }
    }

    /**
     * G3:幂等查询。修正 OAF findIdempotentRun 不区分状态的缺陷——
     * 终态 run 复用结果;running run 返回去重提示(僵尸 running 由 listRun 的 30 分钟兜底收敛)
     */
    private WorkflowTrialRunVo findIdempotentRun(Long agentId, String key) {
        AgentWorkflowRun run = runMapper.selectOne(new LambdaQueryWrapper<AgentWorkflowRun>()
            .eq(AgentWorkflowRun::getAgentId, agentId)
            .eq(AgentWorkflowRun::getIdempotencyKey, key)
            .orderByDesc(AgentWorkflowRun::getId)
            .last("limit 1"));
        if (run == null) {
            return null;
        }
        WorkflowTrialRunVo vo = runResultInternal(run.getId());
        if ("running".equals(run.getStatus())) {
            vo.setMessage("同幂等键的投递正在执行中(运行实例 " + run.getId() + "),本次已去重");
        }
        return vo;
    }

    /**
     * 创建运行实例记录(状态 running),供同步/异步两条链路共用
     */
    private AgentWorkflowRun createRunRow(AgentInfo agent, String workflowJson, String inputs,
                                          Long operatorUserId, String idempotencyKey) {
        AgentWorkflowRun run = new AgentWorkflowRun();
        run.setAgentId(agent.getId());
        run.setWorkspaceId(agent.getWorkspaceId());
        run.setWorkflowJson(workflowJson);
        run.setInputs(inputs == null ? "{}" : inputs);
        run.setIdempotencyKey(idempotencyKey);
        run.setUserId(operatorUserId);
        run.setStatus("running");
        runMapper.insert(run);
        return run;
    }

    /**
     * 执行工作流(试运行与触发器共用):落运行实例 → 按拓扑序执行 → 快照落库
     *
     * @param fromTrigger     触发器链路无登录上下文,knowledge 节点改走内部检索入口跳过登录态权限校验
     * @param dryRun          W8:外部调用节点返回桩输出,不烧 Token
     * @param startNodeKey    W8:从指定节点起跑(上游视为已完成),null 全图执行
     * @param maxSteps        W8:图级步数上限,null 取默认 100
     * @param idempotencyKey  G3:幂等键,非空写入运行实例防重复投递
     */
    private WorkflowTrialRunVo doTrialRun(AgentInfo agent, String workflowJson, String inputs,
                                          Long operatorUserId, boolean fromTrigger,
                                          boolean dryRun, String startNodeKey, Integer maxSteps,
                                          String idempotencyKey) {
        WorkflowValidateVo validate = validateWorkflow(workflowJson);
        if (!Boolean.TRUE.equals(validate.getValid())) {
            throw new ServiceException("工作流校验未通过,无法执行");
        }
        WorkflowTrialRunVo vo = new WorkflowTrialRunVo();
        vo.setInputs(inputs == null ? "{}" : inputs);
        // 按拓扑序真实执行:start 解析输入变量,LLM 节点经 AgentScope ReAct 内核调用模型,
        // approval 节点生成审批单并暂停;P1 起 knowledge/tool 节点已接真实检索与平台工具
        List<WorkflowTrialRunVo.NodeTrace> trace = new ArrayList<>();
        vo.setTrace(trace);
        // 执行回放:所有运行均落运行实例,完整记录图快照/输入/变量/轨迹供事后回放
        AgentWorkflowRun run = createRunRow(agent, workflowJson, vo.getInputs(), operatorUserId, idempotencyKey);
        vo.setRunId(run.getId());
        return doExecuteRun(agent, run, workflowJson, vo.getInputs(), fromTrigger, dryRun, startNodeKey, maxSteps, trace, vo);
    }

    /**
     * 在已创建的运行实例上执行图并落快照(同步/异步链路共用)
     */
    private WorkflowTrialRunVo doExecuteRun(AgentInfo agent, AgentWorkflowRun run, String workflowJson,
                                            String inputs, boolean fromTrigger, boolean dryRun,
                                            String startNodeKey, Integer maxSteps) {
        return doExecuteRun(agent, run, workflowJson, inputs, fromTrigger, dryRun, startNodeKey, maxSteps,
            new ArrayList<>(), null);
    }

    private WorkflowTrialRunVo doExecuteRun(AgentInfo agent, AgentWorkflowRun run, String workflowJson,
                                            String inputs, boolean fromTrigger, boolean dryRun,
                                            String startNodeKey, Integer maxSteps,
                                            List<WorkflowTrialRunVo.NodeTrace> trace, WorkflowTrialRunVo vo) {
        boolean actualDryRun = dryRun;
        int actualMaxSteps = maxSteps == null ? 100 : Math.max(1, Math.min(500, maxSteps));
        if (vo == null) {
            vo = new WorkflowTrialRunVo();
            vo.setInputs(inputs == null ? "{}" : inputs);
            vo.setTrace(trace);
            vo.setRunId(run.getId());
        }
        RunEventBuffer buffer = eventBuffer;
        graphEngine.publishEvent(run.getId(), "meta", Map.of("status", "running", "dryRun", actualDryRun,
            "inputs", inputs == null ? "{}" : inputs));
        Map<String, String> vars = new LinkedHashMap<>();
        Set<String> completed = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        ExecutionOutcome outcome;
        try {
            outcome = graphEngine.executeGraph(workflowJson, vo.getInputs(), agent,
                trace, vars, completed, skipped, run, fromTrigger, actualDryRun, startNodeKey, actualMaxSteps);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            snapshotRun(run, "fail", null, null, vars, trace, completed, skipped);
            graphEngine.publishEvent(run.getId(), "node_error", Map.of("nodeId", "-", "nodeName", "执行异常",
                "nodeType", "system", "error", msg));
            graphEngine.publishEvent(run.getId(), "done", Map.of("status", "fail", "message", "执行失败:" + msg));
            buffer.terminal("wf-" + run.getId(), "error");
            vo.setStatus("fail");
            vo.setMessage("执行失败:" + msg);
            return vo;
        }
        if (outcome.pendingNode() != null) {
            snapshotRun(run, "pending_approval", outcome.pendingNode(), outcome.approvalId(),
                vars, trace, completed, skipped);
            graphEngine.publishEvent(run.getId(), "approval_pending", Map.of("nodeId", outcome.pendingNode(),
                "nodeName", outcome.pendingNodeName() == null ? "" : outcome.pendingNodeName(),
                "approvalId", outcome.approvalId() == null ? 0 : outcome.approvalId()));
            graphEngine.publishEvent(run.getId(), "done", Map.of("status", "pending_approval", "message",
                "已触发人工审批节点[" + outcome.pendingNodeName() + "]"));
            buffer.terminal("wf-" + run.getId(), "pending");
            vo.setStatus("pending_approval");
            vo.setApprovalId(outcome.approvalId());
            vo.setMessage("已触发人工审批节点[" + outcome.pendingNodeName() + "],工作流已暂停,"
                + "请到审批中心处理后自动续跑");
            return vo;
        }
        snapshotRun(run, outcome.success() ? "success" : "fail", null, null,
            vars, trace, completed, skipped);
        graphEngine.publishEvent(run.getId(), "done", Map.of("status", outcome.success() ? "success" : "fail",
            "message", outcome.success() ? "执行完成" : "执行失败:"
                + (trace.isEmpty() ? "未知错误" : trace.get(trace.size() - 1).getOutput())));
        buffer.terminal("wf-" + run.getId(), outcome.success() ? "done" : "error");
        vo.setStatus(outcome.success() ? "success" : "fail");
        vo.setMessage(outcome.success()
            ? "执行完成(LLM 节点经 AgentScope ReAct 内核真实执行)"
            : "执行失败:" + (trace.isEmpty() ? "未知错误" : trace.get(trace.size() - 1).getOutput()));
        return vo;
    }

    public WorkflowTrialRunVo respondRun(Long runId, boolean approved) {
        AgentWorkflowRun run = requireRun(runId);
        // 先校验空间权限再翻转审批状态(此前状态更新在 resumeRun 校验之前,存在越权缺口)
        AgentInfo owner = agentMapper.selectById(run.getAgentId());
        if (owner == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(owner.getWorkspaceId());
        AgentApproval approval = run.getApprovalId() == null ? null : approvalMapper.selectById(run.getApprovalId());
        if (approval == null) {
            throw new ServiceException("运行实例未关联审批单");
        }
        // 体检 P1-4:条件更新防并发——与过期清扫任务互斥,避免「清扫置 expired 覆盖 approved」
        // 与「两人同时通过导致重复续跑重复计费」;带状态条件的更新只有一个成功
        AgentApproval update = new AgentApproval();
        update.setId(approval.getId());
        update.setStatus(approved ? "approved" : "rejected");
        update.setResultNote(approved ? "审批通过,工作流继续执行" : "审批拒绝,工作流终止");
        update.setApproveBy(LoginHelper.getUserId());
        update.setApproveTime(new Date());
        boolean claimed = approvalMapper.update(update, new LambdaUpdateWrapper<AgentApproval>()
            .eq(AgentApproval::getId, approval.getId())
            .eq(AgentApproval::getStatus, "pending")) > 0;
        if (!claimed) {
            throw new ServiceException("该审批单已被处理或已超时");
        }
        return resumeRun(runId, approved);
    }

    public WorkflowTrialRunVo resumeRun(Long runId, boolean approved) {
        AgentWorkflowRun run = requireRun(runId);
        if (!"pending_approval".equals(run.getStatus())) {
            throw new ServiceException("该运行实例不在等待审批状态");
        }
        AgentInfo agent = agentMapper.selectById(run.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        List<WorkflowTrialRunVo.NodeTrace> trace = traceCodec.readTrace(run.getTraceJson());
        Map<String, String> vars = traceCodec.readVars(run.getVarsJson());
        Set<String> completed = traceCodec.readSet(run.getCompletedJson());
        Set<String> skipped = traceCodec.readSet(run.getSkippedJson());
        String pendingNode = run.getPendingNode();
        WorkflowTrialRunVo.NodeTrace approvalTrace = trace.stream()
            .filter(t -> pendingNode.equals(t.getNodeId()) && "pending".equals(t.getStatus()))
            .findFirst().orElse(null);
        WorkflowTrialRunVo vo = new WorkflowTrialRunVo();
        vo.setInputs(run.getInputs());
        vo.setTrace(trace);
        vo.setRunId(run.getId());
        if (!approved) {
            if (approvalTrace != null) {
                approvalTrace.setStatus("fail");
                approvalTrace.setOutput("人工审批拒绝,工作流终止");
            }
            snapshotRun(run, "fail", null, null, vars, trace, completed, skipped);
            vo.setStatus("fail");
            vo.setMessage("人工审批拒绝,工作流已终止");
            return vo;
        }
        if (approvalTrace != null) {
            approvalTrace.setStatus("success");
            approvalTrace.setOutput("人工审批通过,继续执行");
        }
        completed.add(pendingNode);
        ExecutionOutcome outcome = graphEngine.executeGraph(run.getWorkflowJson(), run.getInputs(), agent,
            trace, vars, completed, skipped, run, false, false, null, 100);
        if (outcome.pendingNode() != null) {
            snapshotRun(run, "pending_approval", outcome.pendingNode(), outcome.approvalId(),
                vars, trace, completed, skipped);
            vo.setStatus("pending_approval");
            vo.setApprovalId(outcome.approvalId());
            vo.setMessage("续跑后再次触发人工审批节点[" + outcome.pendingNodeName() + "],工作流继续暂停");
            return vo;
        }
        snapshotRun(run, outcome.success() ? "success" : "fail", null, null, vars, trace, completed, skipped);
        vo.setStatus(outcome.success() ? "success" : "fail");
        vo.setMessage(outcome.success()
            ? "审批通过,工作流续跑完成"
            : "工作流续跑失败:" + (trace.isEmpty() ? "未知错误" : trace.get(trace.size() - 1).getOutput()));
        return vo;
    }

    public AgentWorkflowRun requireRun(Long runId) {
        AgentWorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ServiceException("工作流运行实例不存在");
        }
        return run;
    }

    /**
     * 运行实例状态快照落库(状态/等待节点/审批单/变量与轨迹快照)
     */
    private void snapshotRun(AgentWorkflowRun run, String status, String pendingNode, Long approvalId,
                             Map<String, String> vars, List<WorkflowTrialRunVo.NodeTrace> trace,
                             Set<String> completed, Set<String> skipped) {
        if (run == null) {
            return;
        }
        try {
            run.setStatus(status);
            run.setPendingNode(pendingNode);
            if (approvalId != null) {
                run.setApprovalId(approvalId);
            }
            String varsJson = objectMapper.writeValueAsString(vars);
            // 体检 R3:vars 是 pending_approval 恢复与下游插值的输入,截断会破坏执行语义,
            // 超长仅告警(由 MEDIUMTEXT 承载);展示用的 trace.output 则可安全截断
            if (varsJson.length() > WorkflowTraceCodec.SNAPSHOT_JSON_SOFT_LIMIT) {
                log.warn("[工作流] 运行实例 {} 变量快照较大({} 字符),已保留全文以保恢复语义",
                    run.getId(), varsJson.length());
            }
            run.setVarsJson(varsJson);
            run.setTraceJson(traceCodec.compactTraceJson(trace));
            run.setCompletedJson(objectMapper.writeValueAsString(completed));
            run.setSkippedJson(objectMapper.writeValueAsString(skipped));
            run.setUpdateTime(LocalDateTime.now());
            runMapper.updateById(run);
        } catch (Exception e) {
            throw new ServiceException("运行实例快照写入失败:" + e.getMessage());
        }
    }

    /** 静态图校验:与门面 validateWorkflow 同口径 */
    private WorkflowValidateVo validateWorkflow(String workflowJson) {
        return graphValidator.validateWorkflow(workflowJson);
    }
}
