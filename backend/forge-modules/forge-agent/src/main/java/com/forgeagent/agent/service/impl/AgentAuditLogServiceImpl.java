package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentAuditLog;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentAuditLogBo;
import com.forgeagent.agentcore.domain.vo.AgentAuditLogVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agentcore.mapper.AgentAuditLogMapper;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentAuditLogService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 平台审计日志 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentAuditLogServiceImpl implements IAgentAuditLogService {

    private final AgentAuditLogMapper auditLogMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final AgentWorkspaceMapper workspaceMapper;

    @Override
    public PageResult<AgentAuditLogVo> queryPageList(AgentAuditLogBo bo, PageQuery pageQuery) {
        List<Long> adminWorkspaceIds = resolveScope();
        if (adminWorkspaceIds != null && adminWorkspaceIds.isEmpty()) {
            return PageResult.build(Collections.emptyList(), 0);
        }
        // 部门过滤: 解析该部门下空间集合,与可见范围叠加(取交集)
        if (bo.getDeptId() != null) {
            List<Long> deptWs = workspaceMapper.selectList(new LambdaQueryWrapper<AgentWorkspace>()
                    .eq(AgentWorkspace::getDeptId, bo.getDeptId()))
                .stream().map(AgentWorkspace::getId).distinct().toList();
            if (adminWorkspaceIds != null) {
                deptWs = deptWs.stream().filter(adminWorkspaceIds::contains).toList();
            }
            if (deptWs.isEmpty()) {
                return PageResult.build(Collections.emptyList(), 0);
            }
            adminWorkspaceIds = deptWs;
        }
        LambdaQueryWrapper<AgentAuditLog> wrapper = new LambdaQueryWrapper<AgentAuditLog>()
            .in(adminWorkspaceIds != null, AgentAuditLog::getWorkspaceId, adminWorkspaceIds)
            .eq(bo.getWorkspaceId() != null, AgentAuditLog::getWorkspaceId, bo.getWorkspaceId())
            .eq(StringUtils.isNotBlank(bo.getEventType()), AgentAuditLog::getEventType, bo.getEventType())
            .eq(StringUtils.isNotBlank(bo.getSeverity()), AgentAuditLog::getSeverity, bo.getSeverity())
            .eq(StringUtils.isNotBlank(bo.getResult()), AgentAuditLog::getResult, bo.getResult())
            .eq(StringUtils.isNotBlank(bo.getSourceChannel()), AgentAuditLog::getSourceChannel, bo.getSourceChannel())
            .like(StringUtils.isNotBlank(bo.getOperatorName()), AgentAuditLog::getOperatorName, bo.getOperatorName())
            .ge(StringUtils.isNotBlank(bo.getBeginTime()), AgentAuditLog::getCreateTime, bo.getBeginTime())
            .le(StringUtils.isNotBlank(bo.getEndTime()), AgentAuditLog::getCreateTime, bo.getEndTime())
            .orderByDesc(AgentAuditLog::getId);
        Page<AgentAuditLogVo> page = auditLogMapper.selectVoPage(pageQuery.build(), wrapper);
        fillWorkspaceNames(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentAuditLogVo queryById(Long id) {
        AgentAuditLog entity = auditLogMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("审计日志不存在");
        }
        List<Long> adminWorkspaceIds = resolveScope();
        if (adminWorkspaceIds != null
            && (entity.getWorkspaceId() == null || !adminWorkspaceIds.contains(entity.getWorkspaceId()))) {
            throw new ServiceException("无权查看该审计日志");
        }
        AgentAuditLogVo vo = MapstructUtils.convert(entity, AgentAuditLogVo.class);
        fillWorkspaceNames(List.of(vo));
        return vo;
    }

    /**
     * 可见范围:超管返回 null(不限);其他用户返回其担任管理员的空间ID集合
     */
    private List<Long> resolveScope() {
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        return memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId())
                .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }

    private void fillWorkspaceNames(List<AgentAuditLogVo> list) {
        List<Long> wsIds = list.stream().map(AgentAuditLogVo::getWorkspaceId)
            .filter(Objects::nonNull).distinct().toList();
        if (wsIds.isEmpty()) {
            return;
        }
        Map<Long, String> wsNames = workspaceMapper.selectByIds(wsIds).stream()
            .collect(Collectors.toMap(AgentWorkspace::getId, AgentWorkspace::getName, (a, b) -> a));
        list.forEach(vo -> vo.setWorkspaceName(wsNames.get(vo.getWorkspaceId())));
    }
}
