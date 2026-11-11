package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentWorkspaceMemberBo;
import com.forgeagent.agentcore.domain.vo.AgentWorkspaceMemberVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentWorkspaceMemberService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作空间成员 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentWorkspaceMemberServiceImpl implements IAgentWorkspaceMemberService {

    private final AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;

    @Override
    public PageResult<AgentWorkspaceMemberVo> queryPageList(Long workspaceId, PageQuery pageQuery) {
        permissionHelper.checkWsMember(workspaceId);
        Page<AgentWorkspaceMemberVo> page = memberMapper.selectMemberPage(pageQuery.build(), workspaceId);
        page.getRecords().forEach(v -> v.setRoleLabel(WorkspaceRole.labelOf(v.getRole())));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public List<AgentWorkspaceMemberVo> candidates(Long workspaceId, String keyword) {
        permissionHelper.checkWsAdmin(workspaceId);
        return memberMapper.selectCandidates(workspaceId, keyword);
    }

    @Override
    public Boolean addMember(AgentWorkspaceMemberBo bo) {
        permissionHelper.checkWsAdmin(bo.getWorkspaceId());
        if (!WorkspaceRole.isValid(bo.getRole())) {
            throw new ServiceException("非法的空间角色:" + bo.getRole());
        }
        Long exists = memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
            .eq(AgentWorkspaceMember::getWorkspaceId, bo.getWorkspaceId())
            .eq(AgentWorkspaceMember::getUserId, bo.getUserId()));
        if (exists != null && exists > 0) {
            throw new ServiceException("该用户已是空间成员");
        }
        AgentWorkspaceMember member = new AgentWorkspaceMember();
        member.setWorkspaceId(bo.getWorkspaceId());
        member.setUserId(bo.getUserId());
        member.setRole(bo.getRole());
        boolean ok = memberMapper.insert(member) > 0;
        if (ok) {
            auditLogHelper.record(bo.getWorkspaceId(), "member.invite", "workspace_member", String.valueOf(member.getId()),
                "邀请成员 userId=" + bo.getUserId() + ",角色=" + WorkspaceRole.labelOf(bo.getRole()),
                null,
                java.util.Map.of("userId", bo.getUserId(), "role", bo.getRole(), "roleLabel", WorkspaceRole.labelOf(bo.getRole())),
                "0", "WARN");
        }
        return ok;
    }

    @Override
    public Boolean changeRole(AgentWorkspaceMemberBo bo) {
        AgentWorkspaceMember member = memberMapper.selectById(bo.getId());
        if (member == null) {
            throw new ServiceException("成员记录不存在");
        }
        permissionHelper.checkWsAdmin(member.getWorkspaceId());
        if (!WorkspaceRole.isValid(bo.getRole())) {
            throw new ServiceException("非法的空间角色:" + bo.getRole());
        }
        // 最后一名管理员不可降级
        if (WorkspaceRole.ADMIN.getCode().equals(member.getRole())
            && !WorkspaceRole.ADMIN.getCode().equals(bo.getRole())
            && permissionHelper.adminCount(member.getWorkspaceId()) <= 1) {
            throw new ServiceException("每个空间至少保留一名空间管理员");
        }
        AgentWorkspaceMember update = new AgentWorkspaceMember();
        update.setId(member.getId());
        update.setRole(bo.getRole());
        boolean ok = memberMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(member.getWorkspaceId(), "member.role", "workspace_member", String.valueOf(member.getId()),
                "成员 userId=" + member.getUserId() + " 角色变更:" + WorkspaceRole.labelOf(member.getRole())
                    + "→" + WorkspaceRole.labelOf(bo.getRole()),
                java.util.Map.of("userId", member.getUserId(), "role", member.getRole(),
                    "roleLabel", WorkspaceRole.labelOf(member.getRole())),
                java.util.Map.of("userId", member.getUserId(), "role", bo.getRole(),
                    "roleLabel", WorkspaceRole.labelOf(bo.getRole())),
                "0", "WARN");
        }
        return ok;
    }

    @Override
    public Boolean removeByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentWorkspaceMember member = memberMapper.selectById(id);
            if (member == null) {
                continue;
            }
            permissionHelper.checkWsAdmin(member.getWorkspaceId());
            // 最后一名管理员不可移除
            if (WorkspaceRole.ADMIN.getCode().equals(member.getRole())
                && permissionHelper.adminCount(member.getWorkspaceId()) <= 1) {
                throw new ServiceException("每个空间至少保留一名空间管理员");
            }
            memberMapper.deleteById(id);
            auditLogHelper.record(member.getWorkspaceId(), "member.remove", "workspace_member", String.valueOf(id),
                "移除成员 userId=" + member.getUserId() + "(原角色=" + WorkspaceRole.labelOf(member.getRole()) + ")",
                java.util.Map.of("userId", member.getUserId(), "role", member.getRole(),
                    "roleLabel", WorkspaceRole.labelOf(member.getRole())),
                null, "0", "RISK");
        }
        return true;
    }
}
