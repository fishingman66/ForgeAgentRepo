package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.vo.AgentApprovalVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.agent.service.IAgentWorkflowService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 工具审批单 Service 实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentApprovalServiceImpl implements IAgentApprovalService {

    private final AgentApprovalMapper approvalMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentConfigCacheService agentConfigCache;
    private final WorkspacePermissionHelper permissionHelper;
    /** C4 异步 HITL:待执行工具注册表 */
    private final com.forgeagent.agent.service.runner.PendingApprovalRegistry pendingApprovalRegistry;
    /** C4 异步 HITL:回执经 run 级 SSE 通道推送给访客 */
    private final com.forgeagent.agent.service.run.RunEventBuffer eventBuffer;

    /**
     * 工作流恢复入口(@Lazy 解决与工作流服务的循环依赖)
     */
    @Lazy
    @Autowired
    private IAgentWorkflowService workflowService;

    @Override
    public Long create(Long sessionId, Long agentId, String roundId, String toolName,
                       String toolSource, String serverName, String callArgs) {
        AgentApproval approval = new AgentApproval();
        approval.setSessionId(sessionId);
        approval.setAgentId(agentId);
        approval.setRoundId(roundId);
        approval.setToolName(toolName);
        approval.setToolSource(toolSource);
        approval.setServerName(serverName == null ? "" : serverName);
        approval.setCallArgs(callArgs == null ? "{}" : callArgs);
        approval.setStatus("pending");
        approval.setExecState("PENDING");
        approvalMapper.insert(approval);
        return approval.getId();
    }

    @Override
    public void markPendingExecution(Long id, String runId, Long actionLogId) {
        if (id == null) { return; }
        AgentApproval update = new AgentApproval();
        update.setRunId(runId);
        update.setActionLogId(actionLogId);
        approvalMapper.update(update, new LambdaUpdateWrapper<AgentApproval>()
            .eq(AgentApproval::getId, id));
    }

    @Override
    public void markReplaySnapshot(Long id, Long toolId, String toolDefJson) {
        if (id == null || toolId == null) { return; }
        AgentApproval update = new AgentApproval();
        update.setToolId(toolId);
        update.setToolDefSnapshot(toolDefJson);
        approvalMapper.update(update, new LambdaUpdateWrapper<AgentApproval>()
            .eq(AgentApproval::getId, id));
    }

    @Override
    public boolean markExecState(Long id, String fromState, String toState) {
        if (id == null) { return false; }
        AgentApproval update = new AgentApproval();
        update.setExecState(toState);
        return approvalMapper.update(update, new LambdaUpdateWrapper<AgentApproval>()
            .eq(AgentApproval::getId, id)
            .eq(AgentApproval::getExecState, fromState)) > 0;
    }

    @Override
    public java.util.List<AgentApproval> listStaleApproved() {
        return approvalMapper.selectList(new LambdaQueryWrapper<AgentApproval>()
            .eq(AgentApproval::getStatus, "approved")
            .ne(AgentApproval::getExecState, "DONE")
            .ne(AgentApproval::getExecState, "NEED_MANUAL"));
    }

    @Override
    public String getStatus(Long id) {
        AgentApproval approval = approvalMapper.selectById(id);
        return approval == null ? null : approval.getStatus();
    }

    @Override
    public PageResult<AgentApprovalVo> queryPageList(String status, String keyword, String toolSource, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentApproval> wrapper = new LambdaQueryWrapper<AgentApproval>()
            .eq(StringUtils.isNotBlank(status), AgentApproval::getStatus, status)
            .like(StringUtils.isNotBlank(keyword), AgentApproval::getToolName, keyword)
            .eq(StringUtils.isNotBlank(toolSource), AgentApproval::getToolSource, toolSource)
            .orderByDesc(AgentApproval::getId);
        // 空间范围裁剪:非超管仅可见自己担任管理员的空间内 Agent 的审批单
        List<Long> scopeWsIds = resolveAdminScope();
        if (scopeWsIds != null) {
            if (scopeWsIds.isEmpty()) {
                return PageResult.build();
            }
            List<Long> agentIds = agentInfoMapper.selectList(new LambdaQueryWrapper<AgentInfo>()
                    .select(AgentInfo::getId)
                    .in(AgentInfo::getWorkspaceId, scopeWsIds))
                .stream().map(AgentInfo::getId).toList();
            if (agentIds.isEmpty()) {
                return PageResult.build();
            }
            wrapper.in(AgentApproval::getAgentId, agentIds);
        }
        Page<AgentApprovalVo> page = approvalMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public Boolean handle(Long id, boolean approve, String note) {
        AgentApproval approval = approvalMapper.selectById(id);
        if (approval == null) {
            throw new ServiceException("审批单不存在");
        }
        // 仅审批单所属 Agent 的空间管理员(或超管)可处理
        AgentInfo agent = agentConfigCache.getAgentById(approval.getAgentId());
        if (agent == null) {
            throw new ServiceException("审批单关联的 Agent 不存在");
        }
        permissionHelper.checkWsAdmin(agent.getWorkspaceId());
        if (!"pending".equals(approval.getStatus())) {
            throw new ServiceException("该审批单已处理,当前状态: " + approval.getStatus());
        }
        AgentApproval update = new AgentApproval();
        update.setStatus(approve ? "approved" : "rejected");
        if (approve) { update.setExecState("APPROVED"); }
        update.setResultNote(StringUtils.isBlank(note) ? (approve ? "同意调用" : "拒绝调用") : note);
        update.setApproveBy(LoginHelper.getUserId());
        update.setApproveTime(new Date());
        // 带状态条件的条件更新:并发处理同一审批单时仅一个成功,避免后写覆盖与重复续跑
        LambdaUpdateWrapper<AgentApproval> cond = new LambdaUpdateWrapper<AgentApproval>()
            .eq(AgentApproval::getId, id)
            .eq(AgentApproval::getStatus, "pending");
        if (approve) { cond.eq(AgentApproval::getExecState, "PENDING"); }
        boolean ok = approvalMapper.update(update, cond) > 0;
        if (!ok) {
            throw new ServiceException("该审批单已被处理");
        }
        // 工作流人工审批节点产生的审批单:处理后联动恢复运行实例续跑(失败不回滚审批状态)
        if (ok && "workflow".equals(approval.getToolSource()) && StringUtils.isNotBlank(approval.getRoundId())) {
            try {
                Long runId = Long.parseLong(approval.getRoundId());
                workflowService.resumeRun(runId, approve);
            } catch (Exception e) {
                log.warn("[AgentApproval] 工作流运行实例 {} 恢复失败: {}", approval.getRoundId(), e.getMessage());
            }
        }
        // C4:ask_async 工具审批单 —— 通过后取回暂存的 delegate+param 异步执行并推回执。
        // 条件更新已确保同一审批单仅一次 handle 成功,故这里每个 approvalId 最多消费一次。
        if (ok && !"workflow".equals(approval.getToolSource())) {
            callbackAsyncTool(approval, approve, note);
        }
        return ok;
    }

    /**
     * C4 异步审批回调:取回待执行工具,异步执行后经 run SSE 推结果回执。
     * 拒绝时无需执行工具,只推一条「已拒绝」回执。失败只记日志,不回滚审批状态
     * (与 workflow.resumeRun 分支容错口径一致)。
     */
    private void callbackAsyncTool(AgentApproval approval, boolean approve, String note) {
        com.forgeagent.agent.service.runner.PendingApprovalRegistry.PendingTask task =
            pendingApprovalRegistry.remove(approval.getId());
        if (task == null) {
            // F4:跨实例/重启后进程内注册表丢失——不再静默丢弃
            String storedRunId = approval.getRunId();
            log.warn("[F4] 审批单 {} 已通过但进程内无待执行项(重启/跨实例),标记需人工跟进", approval.getId());
            publishReceipt(storedRunId, "need_manual", approval.getToolName(),
                "操作已审批,但执行实例已变更,正在人工跟进,请稍候");
            return;
        }
        String runId = task.runId();
        String toolName = approval.getToolName();
        if (!approve) {
            publishReceipt(runId, "rejected", toolName,
                StringUtils.isBlank(note) ? "人工审批拒绝了该操作" : note);
            return;
        }
        try {
            java.util.concurrent.CompletableFuture
                .supplyAsync(() -> task.delegate().callAsync(task.param()).block())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("[C4] 审批通过后工具 [{}] 执行失败: {}", toolName, ex.getMessage());
                        publishReceipt(runId, "failed", toolName, "审批已通过,但工具执行失败: " + ex.getMessage());
                    } else {
                        markExecState(approval.getId(), "APPROVED", "DONE");
                        // 只回执执行状态,不把工具原文推给访客(可能含内部/敏感数据)
                        publishReceipt(runId, "approved", toolName, "人工审批已通过,操作已执行完成");
                    }
                });
        } catch (Exception e) {
            log.warn("[C4] 审批回调提交失败 tool={}: {}", toolName, e.getMessage());
            publishReceipt(runId, "failed", toolName, "审批已通过,但回调执行异常: " + e.getMessage());
        }
    }

    /** 推一条工具审批结果回执到 run 级 SSE 通道(未知事件前端可静默忽略)。 */
    private void publishReceipt(String runId, String status, String toolName, String message) {
        if (StringUtils.isBlank(runId) || eventBuffer == null) {
            return;
        }
        try {
            // 不用 JsonUtils:其 <clinit> 依赖 Spring 容器,纯 Mockito 单测类加载即炸
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("eventType", "TOOL_APPROVAL_RESULT", "status", status,
                    "toolName", toolName == null ? "" : toolName,
                    "message", message == null ? "" : message));
            eventBuffer.publish(runId, "cs_approval_result", payload);
        } catch (Exception e) {
            log.warn("[C4] 审批结果回执推送失败 runId={}: {}", runId, e.getMessage());
        }
    }

    @Override
    public int cancelPendingBySession(Long sessionId) {
        AgentApproval probe = new AgentApproval();
        LambdaQueryWrapper<AgentApproval> wrapper = new LambdaQueryWrapper<AgentApproval>()
            .eq(AgentApproval::getSessionId, sessionId)
            .eq(AgentApproval::getStatus, "pending");
        AgentApproval update = new AgentApproval();
        update.setStatus("cancelled");
        update.setResultNote("用户中断了本轮对话,审批单自动取消");
        update.setApproveTime(new Date());
        return approvalMapper.update(update, wrapper);
    }

    @Override
    public void markExpired(Long id) {
        AgentApproval update = new AgentApproval();
        update.setId(id);
        update.setStatus("expired");
        update.setResultNote("审批等待超时,自动过期");
        update.setApproveTime(new Date());
        approvalMapper.updateById(update);
    }

    /**
     * 可见范围:超管不限(null);其他用户返回其担任空间管理员的空间ID集合
     */
    private List<Long> resolveAdminScope() {
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        return memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId())
                .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }
}
