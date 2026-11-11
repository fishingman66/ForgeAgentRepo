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
        approvalMapper.insert(approval);
        return approval.getId();
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
        update.setResultNote(StringUtils.isBlank(note) ? (approve ? "同意调用" : "拒绝调用") : note);
        update.setApproveBy(LoginHelper.getUserId());
        update.setApproveTime(new Date());
        // 带状态条件的条件更新:并发处理同一审批单时仅一个成功,避免后写覆盖与重复续跑
        boolean ok = approvalMapper.update(update, new LambdaUpdateWrapper<AgentApproval>()
            .eq(AgentApproval::getId, id)
            .eq(AgentApproval::getStatus, "pending")) > 0;
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
        return ok;
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
