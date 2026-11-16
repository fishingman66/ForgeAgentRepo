package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.domain.AgentSkillInstall;
import com.forgeagent.agent.domain.bo.AgentSkillBo;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.agent.mapper.AgentSkillInstallMapper;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.agent.service.IAgentSkillService;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 技能包 Service 实现(08方案 / A1)
 *
 * <p>可见性与读写权限沿用 ResourceAccessGuard 口径(平台共享 + 空间私有);
 * 名称唯一性在应用层校验(唯一索引对 NULL workspace_id 不去重,与 MCP 服务器同因)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentSkillServiceImpl implements IAgentSkillService {

    private final AgentSkillMapper skillMapper;
    private final AgentSkillInstallMapper installMapper;
    private final ResourceAccessGuard resourceGuard;

    @Override
    public PageResult<AgentSkillVo> queryPageList(AgentSkillBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentSkill> wrapper = new LambdaQueryWrapper<AgentSkill>()
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentSkill::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentSkill::getStatus, bo.getStatus())
            .orderByDesc(AgentSkill::getId);
        resourceGuard.applyVisible(wrapper, AgentSkill::getWorkspaceId);
        Page<AgentSkillVo> page = skillMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentSkillVo queryById(Long id) {
        AgentSkill entity = skillMapper.selectById(id);
        if (entity != null) {
            resourceGuard.assertReadable(entity.getWorkspaceId());
        }
        AgentSkillVo vo = MapstructUtils.convert(entity, AgentSkillVo.class);
        if (vo == null) {
            throw new ServiceException("技能不存在");
        }
        return vo;
    }

    @Override
    public Boolean insertByBo(AgentSkillBo bo) {
        Long scope = resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), Boolean.TRUE.equals(bo.getShared()));
        checkNameUnique(bo.getName(), null, scope);
        AgentSkill entity = MapstructUtils.convert(bo, AgentSkill.class);
        entity.setWorkspaceId(scope);
        entity.setStatus("0");
        entity.setVersion(1);
        return skillMapper.insert(entity) > 0;
    }

    @Override
    public Boolean updateByBo(AgentSkillBo bo) {
        AgentSkill exists = skillMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("技能不存在");
        }
        resourceGuard.assertWritable(exists.getWorkspaceId());
        checkNameUnique(bo.getName(), bo.getId(), exists.getWorkspaceId());
        AgentSkill update = MapstructUtils.convert(bo, AgentSkill.class);
        update.setStatus(null);
        update.setWorkspaceId(null); // 归属空间一经创建不可变
        // 内容变更版本自增:发布门禁与 KV Cache 语义依赖版本可辨
        update.setVersion(exists.getVersion() == null ? 2 : exists.getVersion() + 1);
        return skillMapper.updateById(update) > 0;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的技能状态");
        }
        AgentSkill exists = skillMapper.selectById(id);
        if (exists != null) {
            resourceGuard.assertWritable(exists.getWorkspaceId());
        }
        AgentSkill update = new AgentSkill();
        update.setId(id);
        update.setStatus(status);
        return skillMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentSkill exists = skillMapper.selectById(id);
            if (exists != null) {
                resourceGuard.assertWritable(exists.getWorkspaceId());
            }
        }
        boolean removed = skillMapper.deleteByIds(ids) > 0;
        if (removed) {
            // 技能删除后安装绑定失去意义,级联解绑
            installMapper.delete(new LambdaQueryWrapper<AgentSkillInstall>()
                .in(AgentSkillInstall::getSkillId, ids));
        }
        return removed;
    }

    @Override
    public Boolean installToAgent(Long skillId, Long agentId) {
        AgentSkill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new ServiceException("技能不存在");
        }
        resourceGuard.assertReadable(skill.getWorkspaceId());
        if (!"0".equals(skill.getStatus())) {
            throw new ServiceException("技能已停用,不能安装");
        }
        Long existsCount = installMapper.selectCount(new LambdaQueryWrapper<AgentSkillInstall>()
            .eq(AgentSkillInstall::getSkillId, skillId)
            .eq(AgentSkillInstall::getAgentId, agentId));
        if (existsCount > 0) {
            return true; // 幂等安装
        }
        AgentSkillInstall install = new AgentSkillInstall();
        install.setSkillId(skillId);
        install.setAgentId(agentId);
        install.setWorkspaceId(skill.getWorkspaceId());
        return installMapper.insert(install) > 0;
    }

    @Override
    public Boolean uninstallFromAgent(Long skillId, Long agentId) {
        return installMapper.delete(new LambdaQueryWrapper<AgentSkillInstall>()
            .eq(AgentSkillInstall::getSkillId, skillId)
            .eq(AgentSkillInstall::getAgentId, agentId)) > 0;
    }

    @Override
    public List<AgentSkill> listInstalledByAgent(Long agentId) {
        List<Long> skillIds = installMapper.selectList(new LambdaQueryWrapper<AgentSkillInstall>()
                .select(AgentSkillInstall::getSkillId)
                .eq(AgentSkillInstall::getAgentId, agentId))
            .stream().map(AgentSkillInstall::getSkillId).distinct().toList();
        if (skillIds.isEmpty()) {
            return List.of();
        }
        return skillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
            .in(AgentSkill::getId, skillIds)
            .eq(AgentSkill::getStatus, "0"));
    }

    @Override
    public AgentSkill getInstalledByName(Long agentId, String name) {
        List<AgentSkill> installed = listInstalledByAgent(agentId);
        return installed.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /** 名称唯一性:同空间(含平台共享 NULL)内查重 */
    private void checkNameUnique(String name, Long excludeId, Long workspaceId) {
        Long count = skillMapper.selectCount(new LambdaQueryWrapper<AgentSkill>()
            .eq(AgentSkill::getName, name)
            .eq(workspaceId != null, AgentSkill::getWorkspaceId, workspaceId)
            .isNull(workspaceId == null, AgentSkill::getWorkspaceId)
            .ne(excludeId != null, AgentSkill::getId, excludeId));
        if (count > 0) {
            throw new ServiceException("同一空间内已存在同名技能");
        }
    }
}
