package com.forgeagent.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 资源域统一守卫(资源域模型双模式)。
 *
 * <p>agent_tool / agent_datasource / agent_model 的 workspace_id 语义:
 * NULL = 平台共享(全空间可读可绑定,仅超管可写); 非空 = 空间私有(仅归属空间可见,
 * 本空间开发者可管)。所有列表、详情、绑定与执行链路只经此守卫判可见性。</p>
 *
 * <p>静态方法为无依赖纯函数,供单测与免登录链路(无 Sa-Token 上下文)复用;
 * 实例方法读登录态与成员关系,不做任何业务 SQL 之外的隐式放行。</p>
 *
 * @author ForgeAgent
 */
@Component
@RequiredArgsConstructor
public class ResourceAccessGuard {

    private final AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;

    /** 关闭后所有资源强制按私有解释(只允许归属空间可见),用于运维一键收紧 */
    @Value("${agent.resource-sharing.enabled:true}")
    private boolean sharingEnabled = true;

    // ------------------------------------------------------------ 纯函数(可单测)

    /** 平台共享判定;开关关闭时不承认共享语义 */
    public static boolean isShared(Long resourceWorkspaceId, boolean sharingEnabled) {
        return sharingEnabled && resourceWorkspaceId == null;
    }

    /** 多空间上下文的读可见性(登录态:用户可属于多个空间) */
    public static boolean isReadable(Long resourceWorkspaceId, Set<Long> callerWorkspaceIds,
                                     boolean superAdmin, boolean sharingEnabled) {
        if (superAdmin) {
            return true;
        }
        if (isShared(resourceWorkspaceId, sharingEnabled)) {
            return true;
        }
        return resourceWorkspaceId != null && callerWorkspaceIds != null
            && callerWorkspaceIds.contains(resourceWorkspaceId);
    }

    /** 单空间上下文的读可见性(免登录链路:工作流归属 Agent 的空间) */
    public static boolean isReadableBy(Long resourceWorkspaceId, Long callerWorkspaceId,
                                       boolean sharingEnabled) {
        if (isShared(resourceWorkspaceId, sharingEnabled)) {
            return true;
        }
        return resourceWorkspaceId != null && resourceWorkspaceId.equals(callerWorkspaceId);
    }

    /** 写权限:共享资源仅超管;私有资源须属于调用方空间(角色另由 checkWsDeveloper 校验) */
    public static boolean isWritable(Long resourceWorkspaceId, Set<Long> callerWorkspaceIds,
                                     boolean superAdmin, boolean sharingEnabled) {
        if (superAdmin) {
            return true;
        }
        if (isShared(resourceWorkspaceId, sharingEnabled)) {
            return false;
        }
        return resourceWorkspaceId != null && callerWorkspaceIds != null
            && callerWorkspaceIds.contains(resourceWorkspaceId);
    }

    // ------------------------------------------------------------ 登录态包装

    public boolean isSuperAdmin() {
        return LoginHelper.isSuperAdmin();
    }

    /** 超管返回 null 表示「全部可见」;普通用户返回其已加入空间集合(可能为空) */
    public Set<Long> callerWorkspaces() {
        if (isSuperAdmin()) {
            return null;
        }
        return new HashSet<>(memberMapper.selectList(
                new LambdaQueryWrapper<AgentWorkspaceMember>()
                    .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).toList());
    }

    /** 列表/详情读校验 */
    public void assertReadable(Long resourceWorkspaceId) {
        if (!isReadable(resourceWorkspaceId, callerWorkspaces(), isSuperAdmin(), sharingEnabled)) {
            throw new ServiceException("资源不存在或无权访问");
        }
    }

    /** 免登录链路读判定(带共享开关):供执行链路按 id 过滤资源 */
    public boolean readableBy(Long resourceWorkspaceId, Long callerWorkspaceId) {
        return isReadableBy(resourceWorkspaceId, callerWorkspaceId, sharingEnabled);
    }

    /** 免登录链路读校验:以工作流归属 Agent 的空间作为调用方上下文 */
    public void assertReadableBy(Long resourceWorkspaceId, Long callerWorkspaceId) {
        if (!isReadableBy(resourceWorkspaceId, callerWorkspaceId, sharingEnabled)) {
            throw new ServiceException("资源不存在或无权访问");
        }
    }

    /** 改/删/启停写校验:共享仅超管;私有再走开发者角色校验 */
    public void assertWritable(Long resourceWorkspaceId) {
        if (!isWritable(resourceWorkspaceId, callerWorkspaces(), isSuperAdmin(), sharingEnabled)) {
            throw new ServiceException("共享资源仅超级管理员可修改,私有资源需归属空间开发者权限");
        }
        if (!isSuperAdmin() && resourceWorkspaceId != null) {
            permissionHelper.checkWsDeveloper(resourceWorkspaceId);
        }
    }

    /**
     * 列表可见性下推到 SQL:(workspace_id IS NULL OR workspace_id IN (...));
     * 超管不加条件;无空间的普通用户只能看共享资源。
     */
    public <T> void applyVisible(LambdaQueryWrapper<T> wrapper, SFunction<T, ?> workspaceColumn) {
        Set<Long> ids = callerWorkspaces();
        if (ids == null) {
            return; // 超管全部可见
        }
        if (ids.isEmpty()) {
            if (sharingEnabled) {
                wrapper.isNull(workspaceColumn);
            } else {
                wrapper.apply("1 = 0"); // 共享关闭且未加入任何空间:不可见任何资源
            }
            return;
        }
        if (sharingEnabled) {
            wrapper.and(q -> q.isNull(workspaceColumn).or().in(workspaceColumn, ids));
        } else {
            wrapper.in(workspaceColumn, ids);
        }
    }

    /**
     * 创建时的归属解析:仅超管可选「平台共享」(NULL);否则必须指定归属空间且具备开发者权限。
     * 编辑场景不调用此方法(workspace_id 一经创建不可变,防止绕过共享资源写权限)。
     */
    public Long resolveCreateWorkspace(Long requestedWorkspaceId, boolean shared) {
        if (shared) {
            if (!isSuperAdmin()) {
                throw new ServiceException("仅超级管理员可创建平台共享资源");
            }
            if (!sharingEnabled) {
                throw new ServiceException("平台共享已关闭,请创建空间私有资源");
            }
            return null;
        }
        if (requestedWorkspaceId == null) {
            throw new ServiceException("请选择归属工作空间");
        }
        permissionHelper.checkWsDeveloper(requestedWorkspaceId);
        return requestedWorkspaceId;
    }

    public boolean isSharingEnabled() {
        return sharingEnabled;
    }
}
