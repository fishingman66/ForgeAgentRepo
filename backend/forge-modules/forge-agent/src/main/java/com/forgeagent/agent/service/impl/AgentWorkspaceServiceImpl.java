package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentWorkspaceBo;
import com.forgeagent.agent.domain.vo.AgentWorkspaceVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentWorkspaceService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工作空间 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentWorkspaceServiceImpl implements IAgentWorkspaceService {

    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;

    @Override
    public List<AgentWorkspaceVo> myWorkspaces(String keyword) {
        Long userId = LoginHelper.getUserId();
        if (LoginHelper.isSuperAdmin()) {
            Page<AgentWorkspaceVo> all = workspaceMapper.selectWorkspacePage(
                new Page<>(1, 500), keyword, "0", null);
            all.getRecords().forEach(v -> v.setMyRole(WorkspaceRole.ADMIN.getCode()));
            return all.getRecords();
        }
        return workspaceMapper.selectMyWorkspaces(userId, keyword);
    }

    @Override
    public PageResult<AgentWorkspaceVo> queryPageList(AgentWorkspaceBo bo, PageQuery pageQuery) {
        Page<AgentWorkspaceVo> page = workspaceMapper.selectWorkspacePage(
            pageQuery.build(), bo.getKeyword(), bo.getStatus(), bo.getDeptId());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentWorkspaceVo queryById(Long id) {
        AgentWorkspaceVo vo = MapstructUtils.convert(workspaceMapper.selectById(id), AgentWorkspaceVo.class);
        if (vo == null) {
            throw new ServiceException("工作空间不存在");
        }
        return vo;
    }

    /** F2 SaaS:注册后自动建空间,显式指定 owner,不读登录态。失败返回 null 不抛。 */
    @Override
    public Long createForUser(Long userId, String userName) {
        if (userId == null || userName == null || userName.isBlank()) {
            return null;
        }
        try {
            String name = "个人空间-" + userName;
            // 名称全局唯一:冲突时加用户 ID 后缀再试一次
            if (workspaceMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentWorkspace>()
                .eq(AgentWorkspace::getName, name)) != null) {
                name = name + "-" + userId;
            }
            AgentWorkspace entity = new AgentWorkspace();
            entity.setName(name);
            entity.setDescription("注册自动创建");
            entity.setStatus("0");
            entity.setOwnerId(userId);
            if (workspaceMapper.insert(entity) <= 0) {
                return null;
            }
            com.forgeagent.agentcore.domain.AgentWorkspaceMember member = new com.forgeagent.agentcore.domain.AgentWorkspaceMember();
            member.setWorkspaceId(entity.getId());
            member.setUserId(userId);
            member.setRole(WorkspaceRole.ADMIN.getCode());
            memberMapper.insert(member);
            auditLogHelper.record(entity.getId(), "workspace.auto_create", "workspace", String.valueOf(entity.getId()),
                "注册自动创建空间[" + name + "]", null,
                java.util.Map.of("name", name, "ownerId", userId), "0", "RISK");
            return entity.getId();
        } catch (Exception e) {
            // fail-safe:建空间失败不影响注册成功,日志告警可追溯
            org.slf4j.LoggerFactory.getLogger(AgentWorkspaceServiceImpl.class)
                .warn("[F2] 注册自动建空间失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AgentWorkspaceBo bo) {
        checkNameUnique(bo.getName(), null);
        AgentWorkspace entity = MapstructUtils.convert(bo, AgentWorkspace.class);
        entity.setStatus("0");
        entity.setOwnerId(LoginHelper.getUserId());
        // 归属部门: 未指定时默认创建者所在部门
        if (entity.getDeptId() == null) {
            entity.setDeptId(LoginHelper.getDeptId());
        }
        boolean flag = workspaceMapper.insert(entity) > 0;
        if (flag) {
            AgentWorkspaceMember member = new AgentWorkspaceMember();
            member.setWorkspaceId(entity.getId());
            member.setUserId(entity.getOwnerId());
            member.setRole(WorkspaceRole.ADMIN.getCode());
            memberMapper.insert(member);
            auditLogHelper.record(entity.getId(), "workspace.create", "workspace", String.valueOf(entity.getId()),
                "创建工作空间[" + entity.getName() + "]",
                null,
                java.util.Map.of("name", entity.getName(), "ownerId", entity.getOwnerId()),
                "0", "RISK");
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AgentWorkspaceBo bo) {
        permissionHelper.checkWsAdmin(bo.getId());
        checkNameUnique(bo.getName(), bo.getId());
        AgentWorkspace old = workspaceMapper.selectById(bo.getId());
        AgentWorkspace update = MapstructUtils.convert(bo, AgentWorkspace.class);
        update.setStatus(null);
        boolean flag = workspaceMapper.updateById(update) > 0;
        // F2:域名变化后旧的校验结果失效,重置为 NONE(避免保存新域名却仍显示上次 RESOLVABLE)
        if (flag && old != null
            && !java.util.Objects.equals(old.getHelpCenterDomain(), bo.getHelpCenterDomain())) {
            workspaceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentWorkspace>()
                .eq(AgentWorkspace::getId, bo.getId())
                .set(AgentWorkspace::getDomainStatus, "NONE")
                .set(AgentWorkspace::getDomainVerifiedAt, null));
        }
        // 归属部门转移审计
        if (flag && old != null && bo.getDeptId() != null
            && !java.util.Objects.equals(old.getDeptId(), bo.getDeptId())) {
            auditLogHelper.record(bo.getId(), "workspace.transfer", "workspace", String.valueOf(bo.getId()),
                "转移空间[" + old.getName() + "]归属部门",
                java.util.Map.of("deptId", String.valueOf(old.getDeptId())),
                java.util.Map.of("deptId", String.valueOf(bo.getDeptId())),
                "0", "RISK");
        }
        return flag;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        permissionHelper.checkWsAdmin(id);
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的空间状态");
        }
        AgentWorkspace update = new AgentWorkspace();
        update.setId(id);
        update.setStatus(status);
        return workspaceMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            permissionHelper.checkWsAdmin(id);
            AgentWorkspace ws = workspaceMapper.selectById(id);
            if (ws == null) {
                throw new ServiceException("工作空间不存在:" + id);
            }
            memberMapper.delete(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getWorkspaceId, id));
            workspaceMapper.deleteById(id);
            auditLogHelper.record(id, "workspace.delete", "workspace", String.valueOf(id),
                "删除工作空间[" + ws.getName() + "]",
                java.util.Map.of("name", ws.getName(), "status", ws.getStatus()),
                null, "0", "RISK");
        }
        return true;
    }

    private void checkNameUnique(String name, Long excludeId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentWorkspace> wrapper = new LambdaQueryWrapper<AgentWorkspace>()
            .eq(AgentWorkspace::getName, name)
            .ne(excludeId != null, AgentWorkspace::getId, excludeId);
        if (workspaceMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("空间名称已存在:" + name);
        }
    }
}
