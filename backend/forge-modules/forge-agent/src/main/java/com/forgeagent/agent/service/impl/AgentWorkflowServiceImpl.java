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
import com.forgeagent.agent.service.workflow.WorkflowTraceCodec;
import com.forgeagent.agent.service.workflow.WorkflowNodeExecutors;
import com.forgeagent.agent.service.workflow.WorkflowGraphEngine;
import com.forgeagent.agent.service.workflow.WorkflowRunLauncher;
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
    private final AgentApprovalMapper approvalMapper;
    private final AgentWorkflowRunMapper runMapper;
    private final AgentWorkflowVersionMapper versionMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;
    /** 知识检索节点绑定知识库的归属校验 */
    private final IAgentKnowledgeService knowledgeService;
    /** W9:试运行进度经 Run 级事件缓冲逐节点推送,支持断线续传 */
    private final RunEventBuffer eventBuffer;
    /** 图编排引擎:审批超时兜底时的事件推送 */
    private final WorkflowGraphEngine graphEngine;
    /** 运行生命周期:触发/异步/SSE/审批/快照 */
    private final WorkflowRunLauncher launcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 轨迹/变量快照编解码(纯 Jackson,可独立单测) */
    private final WorkflowTraceCodec traceCodec = new WorkflowTraceCodec(objectMapper);

    /** 体检质量基建:静态校验提取到 {@link WorkflowGraphValidator}(纯逻辑可单测) */
    private final WorkflowGraphValidator graphValidator = new WorkflowGraphValidator(new ObjectMapper());

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
            Long kbId = WorkflowNodeExecutors.parseLong(node.path("config").path("knowledgeId").asText(""));
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
        return launcher.trialRun(bo);
    }

    @Override
    public Long trialRunAsync(WorkflowTrialRunBo bo) {
        return launcher.trialRunAsync(bo);
    }

    @Override
    public Long startRunForChat(Long agentId, String workflowJson, String inputs, Long userId) {
        return launcher.startRunForChat(agentId, workflowJson, inputs, userId);
    }

    @Override
    public WorkflowTrialRunVo runResultInternal(Long runId) {
        return launcher.runResultInternal(runId);
    }

    @Override
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter runEvents(Long runId, Long after) {
        return launcher.runEvents(runId, after);
    }

    @Override
    public WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId) {
        return launcher.triggerRun(agentId, inputs, operatorUserId);
    }

    @Override
    public WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId, String idempotencyKey) {
        return launcher.triggerRun(agentId, inputs, operatorUserId, idempotencyKey);
    }

    @Override
    public WorkflowTrialRunVo respondRun(Long runId, boolean approved) {
        return launcher.respondRun(runId, approved);
    }

    @Override
    public WorkflowTrialRunVo resumeRun(Long runId, boolean approved) {
        return launcher.resumeRun(runId, approved);
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
        AgentWorkflowRun run = launcher.requireRun(runId);
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
        vo.setTrace(StringUtils.isBlank(run.getTraceJson()) ? new ArrayList<>() : traceCodec.readTrace(run.getTraceJson()));
        return vo;
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
        Long runId = WorkflowNodeExecutors.parseLong(approval.getRoundId());
        if (runId == null) {
            return;
        }
        AgentWorkflowRun run = runMapper.selectById(runId);
        if (run == null || !"pending_approval".equals(run.getStatus())) {
            return;
        }
        List<WorkflowTrialRunVo.NodeTrace> trace = StringUtils.isBlank(run.getTraceJson())
            ? new ArrayList<>() : traceCodec.readTrace(run.getTraceJson());
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
            graphEngine.publishEvent(run.getId(), "node_error", Map.of("nodeId", String.valueOf(run.getPendingNode()),
                "nodeName", "审批节点", "nodeType", "approval", "error", "审批超时自动关闭,工作流终止"));
            graphEngine.publishEvent(run.getId(), "done", Map.of("status", "fail", "message", "审批超时自动关闭"));
            eventBuffer.terminal("wf-" + run.getId(), "error");
        } catch (Exception e) {
            log.warn("[工作流审批] 运行实例终止失败 runId={}: {}", run.getId(), e.getMessage());
        }
    }
}
