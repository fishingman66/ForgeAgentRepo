package com.forgeagent.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

/**
 * 工作空间级权限校验:平台超级管理员放行,否则要求当前用户是空间管理员
 *
 * @author ForgeAgent
 */
@Component
@RequiredArgsConstructor
public class WorkspacePermissionHelper {

    private final AgentWorkspaceMemberMapper memberMapper;

    /**
     * 校验当前用户是否为指定空间的管理员
     *
     * @param workspaceId 空间ID
     * @throws ServiceException 无权限时抛出
     */
    public void checkWsAdmin(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        Long userId = LoginHelper.getUserId();
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
            .eq(AgentWorkspaceMember::getWorkspaceId, workspaceId)
            .eq(AgentWorkspaceMember::getUserId, userId)
            .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()));
        if (count == null || count == 0) {
            throw new ServiceException("仅空间管理员可执行该操作");
        }
    }

    /**
     * 校验当前用户是否为指定空间的成员
     *
     * @param workspaceId 空间ID
     * @throws ServiceException 非成员时抛出
     */
    public void checkWsMember(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        Long userId = LoginHelper.getUserId();
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
            .eq(AgentWorkspaceMember::getWorkspaceId, workspaceId)
            .eq(AgentWorkspaceMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new ServiceException("您不是该工作空间成员");
        }
    }

    /**
     * 校验当前用户是否具备空间开发权限(空间管理员或开发者)
     *
     * @param workspaceId 空间ID
     * @throws ServiceException 无开发权限时抛出
     */
    public void checkWsDeveloper(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        Long userId = LoginHelper.getUserId();
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
            .eq(AgentWorkspaceMember::getWorkspaceId, workspaceId)
            .eq(AgentWorkspaceMember::getUserId, userId)
            .in(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode(), WorkspaceRole.DEVELOPER.getCode()));
        if (count == null || count == 0) {
            throw new ServiceException("仅空间管理员或开发者可执行该操作");
        }
    }

    /**
     * 统计空间内管理员数量
     */
    public long adminCount(Long workspaceId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
            .eq(AgentWorkspaceMember::getWorkspaceId, workspaceId)
            .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()));
        return count == null ? 0 : count;
    }
}
