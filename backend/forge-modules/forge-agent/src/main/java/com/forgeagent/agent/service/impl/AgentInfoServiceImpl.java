package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentTool;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentInfoBo;
import com.forgeagent.agentcore.domain.vo.AgentInfoVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.mapper.AgentToolMapper;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentInfoService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 定义 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentInfoServiceImpl implements IAgentInfoService {

    private final AgentInfoMapper agentMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceMemberMapper workspaceMemberMapper;
    private final AgentModelMapper modelMapper;
    private final AgentToolMapper toolMapper;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentMcpServerMapper mcpServerMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;
    private final com.forgeagent.agentcore.service.AgentConfigCacheService agentConfigCache;
    private final com.forgeagent.agent.service.governance.AgentVersionService versionService;
    private final com.forgeagent.agent.service.governance.ReleaseGateService gateService;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    @Override
    public PageResult<AgentInfoVo> queryPageList(AgentInfoBo bo, PageQuery pageQuery) {
        List<Long> scopeWorkspaceIds = resolveScope(bo.getWorkspaceId());
        if (scopeWorkspaceIds != null && scopeWorkspaceIds.isEmpty()) {
            return PageResult.build(Collections.emptyList(), 0);
        }
        LambdaQueryWrapper<AgentInfo> wrapper = new LambdaQueryWrapper<AgentInfo>()
            .in(scopeWorkspaceIds != null, AgentInfo::getWorkspaceId, scopeWorkspaceIds)
            .eq(bo.getWorkspaceId() != null, AgentInfo::getWorkspaceId, bo.getWorkspaceId())
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentInfo::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentInfo::getStatus, bo.getStatus());
        // 排序经 PageQuery 的 orderByColumn/isAsc 透传(前端传 createTime 等);未指定时默认 ID 倒序
        if (StringUtils.isBlank(pageQuery.getOrderByColumn())) {
            wrapper.orderByDesc(AgentInfo::getId);
        }
        Page<AgentInfoVo> page = agentMapper.selectVoPage(pageQuery.build(), wrapper);
        fillDisplayFields(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentInfoVo queryById(Long id) {
        AgentInfo entity = agentMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsMember(entity.getWorkspaceId());
        AgentInfoVo vo = MapstructUtils.convert(entity, AgentInfoVo.class);
        vo.setToolIds(splitIds(entity.getToolIdsStr()));
        vo.setKnowledgeIds(splitIds(entity.getKnowledgeIdsStr()));
        vo.setMcpIds(splitIds(entity.getMcpIdsStr()));
        vo.setTeamIds(splitIds(entity.getTeamIdsStr()));
        fillDisplayFields(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(AgentInfoBo bo) {
        permissionHelper.checkWsDeveloper(bo.getWorkspaceId());
        checkWorkspaceExists(bo.getWorkspaceId());
        checkNameUnique(bo.getWorkspaceId(), bo.getName(), null);
        validateBindings(bo.getModelId(), bo.getToolIds(), bo.getKnowledgeIds(), bo.getMcpIds(), bo.getWorkspaceId());
        validateOutputConfig(bo, bo.getWorkspaceId(), null);
        AgentInfo entity = MapstructUtils.convert(bo, AgentInfo.class);
        entity.setId(null);
        entity.setToolIdsStr(joinIds(bo.getToolIds()));
        entity.setKnowledgeIdsStr(joinIds(bo.getKnowledgeIds()));
        entity.setMcpIdsStr(joinIds(bo.getMcpIds()));
        applyTeamFields(entity, bo);
        entity.setStatus("0");
        // P1/B1:新 Agent 从 v1 起;未显式指定绑定模式时沿用 MANUAL
        entity.setVersion(1);
        entity.setPromptBindingMode(StringUtils.defaultIfBlank(bo.getPromptBindingMode(), "MANUAL"));
        boolean ok = agentMapper.insert(entity) > 0;
        if (ok) {
            agentConfigCache.evictAgent(entity.getId());
        }
        return ok ? entity.getId() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AgentInfoBo bo) {
        AgentInfo exists = agentMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(exists.getWorkspaceId());
        // P1/B1:已发布 Agent 不允许直接覆盖线上配置,需先停用再改,发布时重新走门禁
        if ("1".equals(exists.getStatus())) {
            throw new ServiceException("已发布 Agent 不可直接修改,请先停用后再编辑并重新发布");
        }
        checkNameUnique(exists.getWorkspaceId(), bo.getName(), bo.getId());
        validateBindings(bo.getModelId(), bo.getToolIds(), bo.getKnowledgeIds(), bo.getMcpIds(), exists.getWorkspaceId());
        validateOutputConfig(bo, exists.getWorkspaceId(), bo.getId());
        AgentInfo update = MapstructUtils.convert(bo, AgentInfo.class);
        update.setWorkspaceId(null);
        update.setStatus(null);
        // 配置版本号递增;前端不传版本字段时由后端统一维护
        int nextVersion = (exists.getVersion() == null ? 1 : exists.getVersion()) + 1;
        update.setVersion(nextVersion);
        update.setToolIdsStr(joinIds(bo.getToolIds()));
        update.setKnowledgeIdsStr(joinIds(bo.getKnowledgeIds()));
        update.setMcpIdsStr(joinIds(bo.getMcpIds()));
        applyTeamFields(update, bo);
        boolean ok = agentMapper.updateById(update) > 0;
        if (ok) {
            agentConfigCache.evictAgent(bo.getId());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeStatus(Long id, String status) {
        AgentInfo exists = agentMapper.selectById(id);
        if (exists == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(exists.getWorkspaceId());
        int publishedVersionNo = 0;
        String configHash = null;
        if ("1".equals(status)) {
            // 发布:必须绑定模型
            if (exists.getModelId() == null) {
                throw new ServiceException("发布前必须绑定模型");
            }
            // P1/B3:发布门禁(基础检查 + 评测/运行时指标 + 基线退化),不通过直接阻断
            gateService.assertCanRelease(exists);
            // P1/B1:冻结完整配置快照,后续任何修改都可追溯与回滚
            publishedVersionNo = versionService.createSnapshot(exists,
                "发布 v" + (exists.getVersion() == null ? 1 : exists.getVersion()), "publish");
            configHash = versionService.hashOf(versionService.buildSnapshot(exists));
        } else if (!"2".equals(status)) {
            throw new ServiceException("非法的目标状态");
        }
        if (status.equals(exists.getStatus())) {
            throw new ServiceException("当前已处于该状态");
        }
        AgentInfo update = new AgentInfo();
        update.setId(id);
        update.setStatus(status);
        if ("1".equals(status)) {
            update.setVersion(publishedVersionNo);
            update.setPublishedVersion(publishedVersionNo);
            update.setConfigHash(configHash);
        }
        boolean ok = agentMapper.updateById(update) > 0;
        if (ok) {
            agentConfigCache.evictAgent(id);
            String eventType = "1".equals(status) ? "agent.publish" : "agent.archive";
            String oldStatus = exists.getStatus();
            auditLogHelper.record(exists.getWorkspaceId(), eventType,
                "agent", String.valueOf(id),
                ("1".equals(status) ? "发布 Agent[" : "归档 Agent[") + exists.getName() + "]",
                java.util.Map.of("status", oldStatus),
                java.util.Map.of("status", status),
                "0", "RISK");
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackVersion(Long agentId, int versionNo) {
        AgentInfo exists = agentMapper.selectById(agentId);
        if (exists == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(exists.getWorkspaceId());
        if ("1".equals(exists.getStatus())) {
            throw new ServiceException("已发布 Agent 不可直接回滚,请先停用后再回滚并重新发布");
        }
        Map<String, Object> snapshot = versionService.snapshotOf(agentId, versionNo);
        AgentInfo update = new AgentInfo();
        update.setId(agentId);
        versionService.applySnapshot(update, snapshot);
        if (update.getName() != null && !update.getName().equals(exists.getName())) {
            checkNameUnique(exists.getWorkspaceId(), update.getName(), agentId);
        }
        int nextVersion = (exists.getVersion() == null ? 1 : exists.getVersion()) + 1;
        update.setVersion(nextVersion);
        agentMapper.updateById(update);
        agentConfigCache.evictAgent(agentId);
        // 回滚本身也留下一条不可变快照,历史链路不断
        AgentInfo rolled = agentMapper.selectById(agentId);
        rolled.setVersion(nextVersion);
        versionService.createSnapshot(rolled, "回滚自 v" + versionNo, "rollback");
        auditLogHelper.record(exists.getWorkspaceId(), "agent.rollback", "agent", String.valueOf(agentId),
            "回滚 Agent[" + exists.getName() + "] 到 v" + versionNo,
            java.util.Map.of("version", exists.getVersion() == null ? 0 : exists.getVersion()),
            java.util.Map.of("fromVersion", versionNo, "newVersion", nextVersion),
            "0", "RISK");
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentInfo exists = agentMapper.selectById(id);
            if (exists == null) {
                continue;
            }
            permissionHelper.checkWsDeveloper(exists.getWorkspaceId());
            agentMapper.deleteById(id);
            agentConfigCache.evictAgent(id);
            auditLogHelper.record(exists.getWorkspaceId(), "agent.delete", "agent", String.valueOf(id),
                "删除 Agent[" + exists.getName() + "]",
                java.util.Map.of("name", exists.getName(), "status", exists.getStatus()),
                null, "0", "RISK");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneById(Long id) {
        AgentInfo source = agentMapper.selectById(id);
        if (source == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsDeveloper(source.getWorkspaceId());
        AgentInfo copy = new AgentInfo();
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setName(dedupeName(source.getWorkspaceId(), source.getName()));
        copy.setDescription(source.getDescription());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setWelcomeMsg(source.getWelcomeMsg());
        copy.setSuggestedQuestions(source.getSuggestedQuestions());
        copy.setModelId(source.getModelId());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setTokenBudget(source.getTokenBudget());
        copy.setOutputSchema(source.getOutputSchema());
        copy.setTeamMode(source.getTeamMode());
        copy.setTeamIdsStr(source.getTeamIdsStr());
        copy.setMemoryEnabled(source.getMemoryEnabled());
        copy.setToolIdsStr(source.getToolIdsStr());
        copy.setKnowledgeIdsStr(source.getKnowledgeIdsStr());
        copy.setMcpIdsStr(source.getMcpIdsStr());
        copy.setWorkflowJson(source.getWorkflowJson());
        copy.setPromptTemplateId(source.getPromptTemplateId());
        copy.setPromptBindingMode(source.getPromptBindingMode());
        copy.setPromptVersionId(source.getPromptVersionId());
        copy.setPromptVariables(source.getPromptVariables());
        copy.setStatus("0");
        copy.setVersion(1);
        boolean ok = agentMapper.insert(copy) > 0;
        if (ok) {
            agentConfigCache.evictAgent(copy.getId());
            auditLogHelper.record(source.getWorkspaceId(), "agent.clone", "agent", String.valueOf(id),
                "克隆 Agent[" + source.getName() + "] → 新 Agent#" + copy.getId(),
                java.util.Map.of("sourceId", id, "sourceName", source.getName()),
                java.util.Map.of("newId", copy.getId(), "newName", copy.getName()),
                "0", "INFO");
        }
        return ok ? copy.getId() : null;
    }

    @Override
    public List<AgentInfoVo> listPublished() {
        LambdaQueryWrapper<AgentInfo> wrapper = new LambdaQueryWrapper<AgentInfo>()
            .eq(AgentInfo::getStatus, "1")
            .orderByDesc(AgentInfo::getId);
        // 按当前用户已加入的空间裁剪,避免全平台 Agent 配置(含 systemPrompt)泄露
        List<Long> scope = resolveScope(null);
        if (scope != null) {
            if (scope.isEmpty()) {
                return List.of();
            }
            wrapper.in(AgentInfo::getWorkspaceId, scope);
        }
        List<AgentInfoVo> list = agentMapper.selectVoList(wrapper);
        fillDisplayFields(list);
        return list;
    }

    /**
     * 计算可见范围:null 表示全部(超管或已校验成员的指定空间),否则为用户已加入的空间集合
     */
    private List<Long> resolveScope(Long workspaceId) {
        if (workspaceId != null) {
            // 显式指定空间时校验成员身份,防止越权查询他空间数据
            permissionHelper.checkWsMember(workspaceId);
            return null;
        }
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        // 普通用户未指定空间: 限定为其已加入的空间
        return workspaceMemberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }

    private void checkWorkspaceExists(Long workspaceId) {
        AgentWorkspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null) {
            throw new ServiceException("工作空间不存在");
        }
    }

    private void checkNameUnique(Long workspaceId, String name, Long excludeId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentInfo> wrapper = new LambdaQueryWrapper<AgentInfo>()
            .eq(AgentInfo::getWorkspaceId, workspaceId)
            .eq(AgentInfo::getName, name)
            .ne(excludeId != null, AgentInfo::getId, excludeId);
        if (agentMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("该空间下已存在同名 Agent:" + name);
        }
    }

    private void validateBindings(Long modelId, List<Long> toolIds, List<Long> knowledgeIds, List<Long> mcpIds, Long workspaceId) {
        if (modelId != null) {
            AgentModel boundModel = modelMapper.selectById(modelId);
            if (boundModel == null) {
                throw new ServiceException("绑定的模型不存在:" + modelId);
            }
            resourceGuard.assertReadableBy(boundModel.getWorkspaceId(), workspaceId);
        }
        if (toolIds != null && !toolIds.isEmpty()) {
            List<AgentTool> boundTools = toolMapper.selectByIds(toolIds);
            long count = boundTools.stream().filter(Objects::nonNull).count();
            if (count != toolIds.stream().distinct().count()) {
                throw new ServiceException("部分绑定的工具不存在");
            }
            for (AgentTool tool : boundTools) {
                resourceGuard.assertReadableBy(tool.getWorkspaceId(), workspaceId);
            }
        }
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            List<AgentKnowledge> kbs = knowledgeMapper.selectByIds(knowledgeIds);
            if (kbs.stream().filter(Objects::nonNull).count() != knowledgeIds.stream().distinct().count()) {
                throw new ServiceException("部分绑定的知识库不存在");
            }
            // 绑定的知识库须属于同一工作空间或为全局知识库(workspace_id IS NULL)
            for (AgentKnowledge kb : kbs) {
                if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(workspaceId)) {
                    throw new ServiceException("知识库[" + kb.getName() + "]不属于当前工作空间,无法绑定");
                }
            }
        }
        if (mcpIds != null && !mcpIds.isEmpty()) {
            long count = mcpServerMapper.selectByIds(mcpIds).stream().filter(Objects::nonNull).count();
            if (count != mcpIds.stream().distinct().count()) {
                throw new ServiceException("部分绑定的 MCP 服务器不存在");
            }
        }
    }

    /**
     * 校验结构化输出与团队协作配置(第四步):
     * Schema 须为合法 JSON 对象且与团队模式互斥;sequential 模式成员须为同空间内存在的其他 Agent。
     */
    private void validateOutputConfig(AgentInfoBo bo, Long workspaceId, Long selfId) {
        String teamMode = StringUtils.isBlank(bo.getTeamMode()) ? "none" : bo.getTeamMode();
        // P2/C2:sequential / parallel / reviewer / supervisor 四种编排模式
        if (!List.of("none", "sequential", "parallel", "reviewer", "supervisor").contains(teamMode)) {
            throw new ServiceException("非法的团队协作模式:" + teamMode);
        }
        boolean hasTeam = !"none".equals(teamMode) && bo.getTeamIds() != null && !bo.getTeamIds().isEmpty();
        if (StringUtils.isNotBlank(bo.getOutputSchema()) && hasTeam) {
            throw new ServiceException("结构化输出与团队协作暂不支持同时启用");
        }
        if (StringUtils.isNotBlank(bo.getOutputSchema())) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(bo.getOutputSchema());
                if (node == null || !node.isObject()) {
                    throw new ServiceException("结构化输出Schema须为 JSON 对象");
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ServiceException("结构化输出Schema不是合法 JSON:" + e.getMessage());
            }
        }
        if ("sequential".equals(teamMode)) {
            if (bo.getTeamIds() == null || bo.getTeamIds().isEmpty()) {
                throw new ServiceException("顺序接力模式须至少选择 1 个协作成员");
            }
            List<Long> distinct = bo.getTeamIds().stream().distinct().toList();
            if (distinct.size() != bo.getTeamIds().size()) {
                throw new ServiceException("协作成员不能重复");
            }
            if (selfId != null && distinct.contains(selfId)) {
                throw new ServiceException("协作成员不能包含当前 Agent 自身");
            }
            List<AgentInfo> members = agentMapper.selectByIds(distinct);
            for (Long memberId : distinct) {
                AgentInfo member = members.stream()
                    .filter(m -> memberId.equals(m.getId())).findFirst().orElse(null);
                if (member == null) {
                    throw new ServiceException("协作成员不存在:" + memberId);
                }
                if (!workspaceId.equals(member.getWorkspaceId())) {
                    throw new ServiceException("协作成员须属于同一工作空间:" + memberId);
                }
            }
        }
    }

    /**
     * 落库团队字段:teamMode 空值归一为 none,none 模式清空成员列表
     */
    private void applyTeamFields(AgentInfo entity, AgentInfoBo bo) {
        String teamMode = StringUtils.isBlank(bo.getTeamMode()) ? "none" : bo.getTeamMode();
        entity.setTeamMode(teamMode);
        entity.setTeamIdsStr("sequential".equals(teamMode) ? joinIds(bo.getTeamIds()) : "");
        entity.setMemoryEnabled(Boolean.TRUE.equals(bo.getMemoryEnabled()) ? "1" : "0");
    }

    private void fillDisplayFields(List<AgentInfoVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> wsIds = list.stream().map(AgentInfoVo::getWorkspaceId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> wsNames = wsIds.isEmpty() ? Map.of() : workspaceMapper.selectByIds(wsIds)
            .stream().collect(Collectors.toMap(AgentWorkspace::getId, AgentWorkspace::getName, (a, b) -> a));
        List<Long> modelIds = list.stream().map(AgentInfoVo::getModelId).filter(Objects::nonNull).distinct().toList();
        Map<Long, AgentModel> modelMap = modelIds.isEmpty() ? Map.of() : agentConfigCache.getModelsByIds(modelIds);
        list.forEach(vo -> {
            vo.setWorkspaceName(vo.getWorkspaceId() == null ? null : wsNames.get(vo.getWorkspaceId()));
            AgentModel model = vo.getModelId() == null ? null : modelMap.get(vo.getModelId());
            vo.setModelName(model == null ? null : model.getName());
            // 会话侧据此决定图片入口是否可用
            vo.setSupportVision(model == null ? null : model.getSupportVision());
        });
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Long> splitIds(String csv) {
        if (StringUtils.isBlank(csv)) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::valueOf).toList();
    }

    /**
     * 空间内名称去重:冲突时追加(副本)/(副本N)
     */
    private String dedupeName(Long workspaceId, String baseName) {
        String candidate = baseName;
        for (int i = 1; i <= 20; i++) {
            Long count = agentMapper.selectCount(new LambdaQueryWrapper<AgentInfo>()
                .eq(AgentInfo::getWorkspaceId, workspaceId)
                .eq(AgentInfo::getName, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
            candidate = baseName + "(副本" + (i == 1 ? "" : String.valueOf(i)) + ")";
        }
        return baseName + "(副本" + System.currentTimeMillis() % 10000 + ")";
    }
}
