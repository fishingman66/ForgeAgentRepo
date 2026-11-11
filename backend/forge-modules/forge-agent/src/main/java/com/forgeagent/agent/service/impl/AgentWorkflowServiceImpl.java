package com.forgeagent.agent.service.impl;

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

/**
 * 工作流编排 Service 实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentWorkflowServiceImpl implements IAgentWorkflowService {

    private final AgentInfoMapper agentMapper;
    private final AgentConfigCacheService agentConfigCache;
    private final AgentModelMapper modelMapper;
    private final AgentApprovalMapper approvalMapper;
    private final AgentWorkflowRunMapper runMapper;
    private final AgentWorkflowVersionMapper versionMapper;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final WorkspacePermissionHelper permissionHelper;
    private final SandboxCodeExecutor sandboxCodeExecutor;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;
    /** P1/B4:知识检索节点接真实混合检索,工具节点接平台工具装配 */
    private final IAgentKnowledgeService knowledgeService;
    private final ChatContextAssembler contextAssembler;
    private final ToolAssembler toolAssembler;
    /** W9:试运行进度经 Run 级事件缓冲逐节点推送,支持断线续传 */
    private final RunEventBuffer eventBuffer;
    /** 体检 S3:http 节点 SSRF 防护开关等运行时配置 */
    private final com.forgeagent.agent.config.AgentRuntimeProperties runtimeProperties;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** W9:异步试运行执行池(先落库返回 runId,后台执行) */
    private static final ExecutorService ASYNC_RUN_EXECUTOR = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
        r -> {
            Thread t = new Thread(r, "wf-async-run");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());

    /** 体检质量基建:静态校验提取到 {@link WorkflowGraphValidator}(纯逻辑可单测) */
    private final WorkflowGraphValidator graphValidator = new WorkflowGraphValidator(new ObjectMapper());

    /** W8:dryRun 桩输出覆盖的外部调用节点类型(节点级超时不覆盖 knowledge,避免脱离登录态线程) */
    private static final Set<String> EXTERNAL_NODE_TYPES = Set.of("llm", "tool", "http", "loop", "code", "knowledge");

    /**
     * HTTP 节点允许的请求方法(定义收敛到校验器,执行器与校验器共用)
     */
    private static final Set<String> HTTP_METHODS = WorkflowGraphValidator.HTTP_METHODS;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveWorkflow(WorkflowGraphBo bo) {
        AgentInfo agent = agentMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        // P1/B2 修订:已发布 Agent 允许编辑工作流草稿——发布时的配置快照已在 agent_version 冻结,
        // 编辑草稿只影响下一次发布,不破坏版本治理与回滚;其他基础配置(提示词/模型绑定等)仍需停用后修改
        boolean publishedDraft = "1".equals(agent.getStatus());
        // 保存前做基础校验
        WorkflowValidateVo validate = validateWorkflow(bo.getWorkflowJson());
        if (!Boolean.TRUE.equals(validate.getValid())) {
            throw new ServiceException("工作流校验未通过:" +
                (validate.getBlockers().isEmpty() ? "未知错误" : validate.getBlockers().get(0).getMessage()));
        }
        // 体检 S2:保存时校验知识库节点归属——保存者(登录态)对每个引用的知识库必须有读权限,
        // 堵住「画布写他空间 knowledgeId 后经触发器/API/聊天链路经 searchInternal 外泄」的路径
        checkKnowledgeOwnership(bo.getWorkflowJson());
        AgentInfo update = new AgentInfo();
        update.setId(bo.getAgentId());
        update.setWorkflowJson(bo.getWorkflowJson());
        agentMapper.updateById(update);
        // 直接改写 agent_info,必须同步清热点配置缓存,否则对话侧仍读到旧 workflowJson
        agentConfigCache.evictAgent(bo.getAgentId());
        // 同步生成递增版本快照,支撑历史版本查看与回滚
        Long version = createVersion(agent, bo.getWorkflowJson(), bo.getRemark());
        // 审计记录真实节点数(此前误把 JSON 字符串长度当 nodeCount,指标失真)
        int nodeCount = 0;
        try {
            nodeCount = objectMapper.readTree(bo.getWorkflowJson()).path("nodes").size();
        } catch (Exception ignored) {
            // 审计字段容错,JSON 已在前面的校验中解析成功过
        }
        auditLogHelper.record(agent.getWorkspaceId(), "workflow.save", "agent_workflow", String.valueOf(agent.getId()),
            publishedDraft
                ? "已发布 Agent 编辑工作流草稿并生成版本 v" + version + "(需重新发布生效)"
                : "保存工作流草稿并生成版本 v" + version, null,
            java.util.Map.of("version", version, "nodeCount", nodeCount, "publishedDraft", publishedDraft),
            "0", "WARN");
        return version;
    }

    /**
     * 体检 S2:保存链路的知识库归属校验(登录态上下文内执行)。
     * queryById 内部走 checkWsReadable——保存者对本空间/全局知识库有读权限,引用他空间直接报错。
     */
    private void checkKnowledgeOwnership(String workflowJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            return; // JSON 合法性由 validateWorkflow 负责
        }
        for (JsonNode node : root.path("nodes")) {
            if (!"knowledge".equals(node.path("type").asText(""))) {
                continue;
            }
            Long kbId = parseLong(node.path("config").path("knowledgeId").asText(""));
            if (kbId != null) {
                knowledgeService.queryById(kbId);
            }
        }
    }

    /**
     * 生成工作流版本快照(版本号按 Agent 递增)
     *
     * @return 新版本号
     */
    private Long createVersion(AgentInfo agent, String workflowJson, String remark) {
        // 按 Agent 加锁串行化版本号分配,避免并发保存产生重复版本号
        org.redisson.api.RLock lock = RedisUtils.getClient().getLock("agent:workflow:version:" + agent.getId());
        lock.lock();
        try {
            List<Object> objs = versionMapper.selectObjs(new QueryWrapper<AgentWorkflowVersion>()
                .select("max(version) as v").eq("agent_id", agent.getId()));
            Object max = objs.isEmpty() ? null : objs.get(0);
            long next = max == null ? 1L : Long.parseLong(max.toString()) + 1L;
            AgentWorkflowVersion v = new AgentWorkflowVersion();
            v.setAgentId(agent.getId());
            v.setWorkspaceId(agent.getWorkspaceId());
            v.setVersion((int) next);
            v.setWorkflowJson(workflowJson);
            v.setRemark(StringUtils.isBlank(remark) ? "保存草稿 v" + next : remark);
            versionMapper.insert(v);
            return next;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public WorkflowValidateVo validateWorkflow(String workflowJson) {
        // 质量基建批次:静态校验全家提取到 WorkflowGraphValidator(纯逻辑,可单测)
        return graphValidator.validateWorkflow(workflowJson);
    }

    @Override
    public WorkflowTrialRunVo trialRun(WorkflowTrialRunBo bo) {
        AgentInfo agent = agentMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        return doTrialRun(agent, bo.getWorkflowJson(), bo.getInputs(), LoginHelper.getUserId(), false,
            Boolean.TRUE.equals(bo.getDryRun()), bo.getStartNodeKey(), bo.getMaxSteps(), null);
    }

    @Override
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

    @Override
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

    @Override
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
            ? new ArrayList<>() : readTrace(run.getTraceJson()));
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
                publishEvent(run.getId(), "node_error",
                    Map.of("nodeId", "-", "nodeName", "执行异常", "nodeType", "system",
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                publishEvent(run.getId(), "done", Map.of("status", "fail", "message", "后台执行异常:" + e.getMessage()));
                eventBuffer.terminal("wf-" + run.getId(), "error");
            }
        });
        return run.getId();
    }

    @Override
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

    @Override
    public WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId) {
        return triggerRun(agentId, inputs, operatorUserId, null);
    }

    @Override
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
        publishEvent(run.getId(), "meta", Map.of("status", "running", "dryRun", actualDryRun,
            "inputs", inputs == null ? "{}" : inputs));
        Map<String, String> vars = new LinkedHashMap<>();
        Set<String> completed = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        ExecutionOutcome outcome;
        try {
            outcome = executeGraph(workflowJson, vo.getInputs(), agent,
                trace, vars, completed, skipped, run, fromTrigger, actualDryRun, startNodeKey, actualMaxSteps);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            snapshotRun(run, "fail", null, null, vars, trace, completed, skipped);
            publishEvent(run.getId(), "node_error", Map.of("nodeId", "-", "nodeName", "执行异常",
                "nodeType", "system", "error", msg));
            publishEvent(run.getId(), "done", Map.of("status", "fail", "message", "执行失败:" + msg));
            buffer.terminal("wf-" + run.getId(), "error");
            vo.setStatus("fail");
            vo.setMessage("执行失败:" + msg);
            return vo;
        }
        if (outcome.pendingNode() != null) {
            snapshotRun(run, "pending_approval", outcome.pendingNode(), outcome.approvalId(),
                vars, trace, completed, skipped);
            publishEvent(run.getId(), "approval_pending", Map.of("nodeId", outcome.pendingNode(),
                "nodeName", outcome.pendingNodeName() == null ? "" : outcome.pendingNodeName(),
                "approvalId", outcome.approvalId() == null ? 0 : outcome.approvalId()));
            publishEvent(run.getId(), "done", Map.of("status", "pending_approval", "message",
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
        publishEvent(run.getId(), "done", Map.of("status", outcome.success() ? "success" : "fail",
            "message", outcome.success() ? "执行完成" : "执行失败:"
                + (trace.isEmpty() ? "未知错误" : trace.get(trace.size() - 1).getOutput())));
        buffer.terminal("wf-" + run.getId(), outcome.success() ? "done" : "error");
        vo.setStatus(outcome.success() ? "success" : "fail");
        vo.setMessage(outcome.success()
            ? "执行完成(LLM 节点经 AgentScope ReAct 内核真实执行)"
            : "执行失败:" + (trace.isEmpty() ? "未知错误" : trace.get(trace.size() - 1).getOutput()));
        return vo;
    }

    /** W9:工作流事件统一出口,写入失败只留日志不阻断执行 */
    private void publishEvent(Long runId, String event, Map<?, ?> payload) {
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

    @Override
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

    @Override
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
        List<WorkflowTrialRunVo.NodeTrace> trace = readTrace(run.getTraceJson());
        Map<String, String> vars = readVars(run.getVarsJson());
        Set<String> completed = readSet(run.getCompletedJson());
        Set<String> skipped = readSet(run.getSkippedJson());
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
        ExecutionOutcome outcome = executeGraph(run.getWorkflowJson(), run.getInputs(), agent,
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

    private AgentWorkflowRun requireRun(Long runId) {
        AgentWorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ServiceException("工作流运行实例不存在");
        }
        return run;
    }

    @Override
    public List<AgentWorkflowVersionVo> listVersion(Long agentId) {
        AgentInfo agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        // 列表不返回图快照大字段,详情接口单独获取
        return versionMapper.selectVoList(new LambdaQueryWrapper<AgentWorkflowVersion>()
            .select(AgentWorkflowVersion::getId, AgentWorkflowVersion::getAgentId,
                AgentWorkflowVersion::getVersion, AgentWorkflowVersion::getRemark,
                AgentWorkflowVersion::getCreateBy, AgentWorkflowVersion::getCreateTime)
            .eq(AgentWorkflowVersion::getAgentId, agentId)
            .orderByDesc(AgentWorkflowVersion::getVersion));
    }

    @Override
    public AgentWorkflowVersionVo getVersion(Long versionId) {
        AgentWorkflowVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new ServiceException("工作流版本不存在");
        }
        permissionHelper.checkWsDeveloper(v.getWorkspaceId());
        AgentWorkflowVersionVo vo = new AgentWorkflowVersionVo();
        vo.setId(v.getId());
        vo.setAgentId(v.getAgentId());
        vo.setVersion(v.getVersion());
        vo.setRemark(v.getRemark());
        vo.setWorkflowJson(v.getWorkflowJson());
        vo.setCreateBy(v.getCreateBy());
        vo.setCreateTime(v.getCreateTime());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long rollbackVersion(Long versionId) {
        AgentWorkflowVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new ServiceException("工作流版本不存在");
        }
        AgentInfo agent = agentMapper.selectById(v.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        // P1/B2 修订:与 saveWorkflow 同口径,已发布 Agent 允许回滚工作流草稿(需重新发布生效)
        // 回滚目标快照当初保存时已通过校验,直接写回草稿;
        // 若后续校验规则升级导致不兼容,保存时会再次拦截提示
        AgentInfo update = new AgentInfo();
        update.setId(agent.getId());
        update.setWorkflowJson(v.getWorkflowJson());
        agentMapper.updateById(update);
        agentConfigCache.evictAgent(agent.getId());
        Long newVersion = createVersion(agent, v.getWorkflowJson(), "回滚自 v" + v.getVersion());
        auditLogHelper.record(agent.getWorkspaceId(), "workflow.rollback", "agent_workflow", String.valueOf(agent.getId()),
            "回滚工作流至版本 v" + v.getVersion() + ",生成新版本 v" + newVersion,
            java.util.Map.of("from", "draft", "version", v.getVersion()),
            java.util.Map.of("newVersion", newVersion, "sourceVersion", v.getVersion()),
            "0", "RISK");
        return newVersion;
    }

    @Override
    public PageResult<AgentWorkflowRunVo> listRun(Long agentId, PageQuery pageQuery) {
        AgentInfo agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        markStaleRunningRuns(agentId);
        Page<AgentWorkflowRunVo> page = runMapper.selectVoPage(pageQuery.build(),
            new LambdaQueryWrapper<AgentWorkflowRun>()
                .eq(AgentWorkflowRun::getAgentId, agentId)
                .orderByDesc(AgentWorkflowRun::getId));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * W4:僵尸运行记录兜底。试运行是同步执行,进程中途重启会让 run 永远停在 running;
     * 查询运行列表时把超过 30 分钟仍在 running 的记录标记为 fail,避免回放入口出现假「运行中」
     */
    private void markStaleRunningRuns(Long agentId) {
        try {
            runMapper.update(null, new LambdaUpdateWrapper<AgentWorkflowRun>()
                .eq(AgentWorkflowRun::getAgentId, agentId)
                .eq(AgentWorkflowRun::getStatus, "running")
                .le(AgentWorkflowRun::getCreateTime, LocalDateTime.now().minusMinutes(30))
                .set(AgentWorkflowRun::getStatus, "fail")
                .set(AgentWorkflowRun::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            // 兜底逻辑失败不影响正常查询
        }
    }

    @Override
    public WorkflowRunDetailVo getRunDetail(Long runId) {
        AgentWorkflowRun run = requireRun(runId);
        permissionHelper.checkWsDeveloper(run.getWorkspaceId());
        WorkflowRunDetailVo vo = new WorkflowRunDetailVo();
        vo.setId(run.getId());
        vo.setAgentId(run.getAgentId());
        vo.setStatus(run.getStatus());
        vo.setInputs(run.getInputs());
        vo.setWorkflowJson(run.getWorkflowJson());
        vo.setVarsJson(run.getVarsJson());
        vo.setPendingNode(run.getPendingNode());
        vo.setApprovalId(run.getApprovalId());
        vo.setUserId(run.getUserId());
        vo.setCreateTime(run.getCreateTime());
        vo.setTrace(StringUtils.isBlank(run.getTraceJson()) ? new ArrayList<>() : readTrace(run.getTraceJson()));
        return vo;
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
            if (varsJson.length() > SNAPSHOT_JSON_SOFT_LIMIT) {
                log.warn("[工作流] 运行实例 {} 变量快照较大({} 字符),已保留全文以保恢复语义",
                    run.getId(), varsJson.length());
            }
            run.setVarsJson(varsJson);
            run.setTraceJson(compactTraceJson(trace));
            run.setCompletedJson(objectMapper.writeValueAsString(completed));
            run.setSkippedJson(objectMapper.writeValueAsString(skipped));
            run.setUpdateTime(LocalDateTime.now());
            runMapper.updateById(run);
        } catch (Exception e) {
            throw new ServiceException("运行实例快照写入失败:" + e.getMessage());
        }
    }

    /** 快照 JSON 软上限:超过后才做轨迹裁剪,常态零开销 */
    private static final int SNAPSHOT_JSON_SOFT_LIMIT = 256 * 1024;

    /** 单节点轨迹 output 上限(展示用,不参与执行) */
    private static final int NODE_OUTPUT_MAX_CHARS = 4000;

    /**
     * 体检 R3:轨迹快照裁剪。LLM/HTTP/loop 节点输出全文累积(100 步图可达数百 KB),
     * 超限时把每条 output 截到 4000 字符并加标记;仅在 JSON 树上操作,
     * 不改动内存中的 trace 对象(事件流与本次返回结果仍为全文)。
     */
    private String compactTraceJson(List<WorkflowTrialRunVo.NodeTrace> trace)
        throws com.fasterxml.jackson.core.JsonProcessingException {
        String json = objectMapper.writeValueAsString(trace);
        if (json.length() <= SNAPSHOT_JSON_SOFT_LIMIT) {
            return json;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : root) {
                    if (n instanceof com.fasterxml.jackson.databind.node.ObjectNode obj
                        && obj.has("output")) {
                        String out = obj.path("output").asText("");
                        if (out.length() > NODE_OUTPUT_MAX_CHARS) {
                            obj.put("output", out.substring(0, NODE_OUTPUT_MAX_CHARS)
                                + "\n...(轨迹输出过长已截断)");
                        }
                    }
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[工作流] 轨迹快照裁剪失败,保留原轨迹: {}", e.getMessage());
            return json;
        }
    }

    private List<WorkflowTrialRunVo.NodeTrace> readTrace(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, WorkflowTrialRunVo.NodeTrace.class));
        } catch (Exception e) {
            throw new ServiceException("运行轨迹快照解析失败:" + e.getMessage());
        }
    }

    private Map<String, String> readVars(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Set<String> readSet(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(LinkedHashSet.class, String.class));
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    /**
     * 执行结果:success 正常结束;pendingNode 非空表示触发人工审批暂停
     */
    private record ExecutionOutcome(boolean success, String pendingNode, String pendingNodeName,
                                    Long approvalId) {
    }

    /** W7:出边信息(branchName 为条件分支名,W6 起条件出边按分支名关联,空名兼容旧数据走下标) */
    /** 图算法委托 {@link WorkflowGraphValidator#bfs} */
    private Set<String> bfs(String start, Map<String, List<String>> adj) {
        return WorkflowGraphValidator.bfs(start, adj);
    }

    /** W5/W8:一次图执行的上下文(dryRun/步数上限/执行身份/入参) */
    private record RunContext(AgentInfo agent, AgentWorkflowRun run, boolean fromTrigger,
                              boolean dryRun, int maxSteps, AtomicInteger steps, String inputs) {
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
     * 人工审批暂停信号:审批单已生成,携带等待节点与审批单ID
     */
    private static class ApprovalPauseException extends RuntimeException {
        private final String nodeId;
        private final String nodeName;
        private final Long approvalId;

        ApprovalPauseException(String nodeId, String nodeName, Long approvalId) {
            super("等待人工审批:" + nodeName);
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.approvalId = approvalId;
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
    private ExecutionOutcome executeGraph(String workflowJson, String inputs, AgentInfo agent,
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
        RunContext ctx = new RunContext(agent, run, fromTrigger, dryRun, maxSteps, new AtomicInteger(0), inputs);
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
                    "output", truncateForTrace(t.getOutput(), 2000)));
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
                                         Map<String, String> vars, RunContext ctx) {
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
                    selected = executeNode(node, t, vars, ctx);
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
                                    Map<String, String> vars, RunContext ctx, long timeoutMs) {
        Future<?> future = NODE_TIMEOUT_EXECUTOR.submit(() -> executeNode(node, t, vars, ctx));
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
    private void executeParallelNode(JsonNode node, WorkflowTrialRunVo.NodeTrace t, RunContext ctx,
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
            "output", truncateForTrace(t.getOutput(), 2000)));
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
    private BranchResult runBranch(String startId, String joinId, RunContext ctx,
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

    /**
     * 执行单个节点并注册输出变量
     *
     * @return 条件节点返回选中的分支名称,其他节点返回 null
     */
    private String executeNode(JsonNode node, WorkflowTrialRunVo.NodeTrace t,
                               Map<String, String> vars, RunContext ctx) {
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
                                      Map<String, String> vars, RunContext ctx) {
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

    private static Long parseLong(String raw) {
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
                + response.statusCode() + ":" + truncateForTrace(bodyText, 500));
        }
        vars.put(t.getNodeName() + ".output", bodyText);
        t.setOutput("HTTP " + response.statusCode() + ",响应 " + bodyText.length() + " 字符:\n"
            + truncateForTrace(bodyText, 2000));
        t.setStatus("success");
    }

    private String truncateForTrace(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...(过长已截断)";
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

    /**
     * 体检 R4:僵尸 running 运行实例主动兜底(每 5 分钟,全 Agent)。
     * 此前 30 分钟兜底只在 listRun 查询时被动触发——进程重启后的悬挂 run
     * 在无人访问运行列表时会永久停在 running(回放入口出现假「运行中」)。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000L, initialDelay = 120_000L)
    public void sweepStaleRuns() {
        try {
            int n = runMapper.update(null, new LambdaUpdateWrapper<AgentWorkflowRun>()
                .eq(AgentWorkflowRun::getStatus, "running")
                .le(AgentWorkflowRun::getCreateTime, LocalDateTime.now().minusMinutes(30))
                .set(AgentWorkflowRun::getStatus, "fail")
                .set(AgentWorkflowRun::getUpdateTime, LocalDateTime.now()));
            if (n > 0) {
                log.info("[工作流] 僵尸运行实例清扫,置 fail {} 条", n);
            }
        } catch (Exception e) {
            log.warn("[工作流] 僵尸运行实例清扫失败: {}", e.getMessage());
        }
    }

    /**
     * G1:工作流审批过期清扫(每 5 分钟)。超时未处理的审批单置 expired,
     * 关联的 pending_approval 运行实例置 fail 并标记等待节点,避免永久悬挂。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void sweepExpiredApprovals() {
        List<AgentApproval> expired;
        try {
            expired = approvalMapper.selectList(new LambdaQueryWrapper<AgentApproval>()
                .eq(AgentApproval::getStatus, "pending")
                .isNotNull(AgentApproval::getExpiredAt)
                .le(AgentApproval::getExpiredAt, new Date()));
        } catch (Exception e) {
            log.warn("[工作流审批] 过期清扫查询失败: {}", e.getMessage());
            return;
        }
        for (AgentApproval approval : expired) {
            try {
                // 体检 P1-4:条件更新——与审批决策(respondRun)互斥,决策先行则清扫放弃
                boolean claimed = approvalMapper.update(null, new LambdaUpdateWrapper<AgentApproval>()
                    .eq(AgentApproval::getId, approval.getId())
                    .eq(AgentApproval::getStatus, "pending")
                    .set(AgentApproval::getStatus, "expired")
                    .set(AgentApproval::getResultNote, "审批超时自动关闭")
                    .set(AgentApproval::getUpdateTime, LocalDateTime.now())) > 0;
                if (claimed) {
                    failPendingWorkflowRun(approval);
                }
            } catch (Exception e) {
                log.warn("[工作流审批] 过期清扫失败 approvalId={}: {}", approval.getId(), e.getMessage());
            }
        }
    }

    /** G1:审批单过期后,把等待它的工作流运行实例终止为 fail */
    private void failPendingWorkflowRun(AgentApproval approval) {
        if (!"workflow".equals(approval.getToolSource()) || StringUtils.isBlank(approval.getRoundId())) {
            return;
        }
        Long runId = parseLong(approval.getRoundId());
        if (runId == null) {
            return;
        }
        AgentWorkflowRun run = runMapper.selectById(runId);
        if (run == null || !"pending_approval".equals(run.getStatus())) {
            return;
        }
        List<WorkflowTrialRunVo.NodeTrace> trace = StringUtils.isBlank(run.getTraceJson())
            ? new ArrayList<>() : readTrace(run.getTraceJson());
        for (WorkflowTrialRunVo.NodeTrace t : trace) {
            if (run.getPendingNode() != null && run.getPendingNode().equals(t.getNodeId())
                && "pending".equals(t.getStatus())) {
                t.setStatus("fail");
                t.setOutput("审批超时自动关闭,工作流终止");
            }
        }
        try {
            AgentWorkflowRun update = new AgentWorkflowRun();
            update.setId(run.getId());
            update.setStatus("fail");
            update.setTraceJson(objectMapper.writeValueAsString(trace));
            update.setUpdateTime(LocalDateTime.now());
            runMapper.updateById(update);
            publishEvent(run.getId(), "node_error", Map.of("nodeId", String.valueOf(run.getPendingNode()),
                "nodeName", "审批节点", "nodeType", "approval", "error", "审批超时自动关闭,工作流终止"));
            publishEvent(run.getId(), "done", Map.of("status", "fail", "message", "审批超时自动关闭"));
            eventBuffer.terminal("wf-" + run.getId(), "error");
        } catch (Exception e) {
            log.warn("[工作流审批] 运行实例终止失败 runId={}: {}", run.getId(), e.getMessage());
        }
    }
}
