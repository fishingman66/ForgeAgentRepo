package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.domain.AgentSkillInstall;
import com.forgeagent.agent.domain.bo.AgentSkillBo;
import com.forgeagent.agent.domain.vo.AgentSkillBindVo;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.agent.domain.vo.SkillInstallTargetVo;
import com.forgeagent.agent.mapper.AgentSkillInstallMapper;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.agent.service.IAgentSkillService;
import com.forgeagent.agent.service.support.AgentSkillVersionService;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final AgentInfoMapper agentMapper;
    private final ResourceAccessGuard resourceGuard;
    private final AgentSkillVersionService skillVersionService;

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
        boolean ok = skillMapper.insert(entity) > 0;
        if (ok) {
            // P1-B:首版即归档,保证快照里的 contentHash 永远有出处
            skillVersionService.archive(entity);
        }
        return ok;
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
        boolean ok = skillMapper.updateById(update) > 0;
        if (ok) {
            // 只归档新版本,旧版本内容保持不可变;已钉旧版的 Agent 不受影响
            skillVersionService.archive(skillMapper.selectById(bo.getId()));
        }
        return ok;
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
            // 技能删除后安装绑定失去意义,级联解绑(物理删除,避免残留占用 uk_skill_agent)
            installMapper.physicalUnbindBySkillIds(ids);
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
        // 历史卸载若留下逻辑删除记录,仍占用 uk_skill_agent,直接插入会报 Duplicate entry
        installMapper.physicalUnbind(skillId, agentId);
        AgentSkillInstall install = new AgentSkillInstall();
        install.setSkillId(skillId);
        install.setAgentId(agentId);
        install.setWorkspaceId(skill.getWorkspaceId());
        // P1-B:安装即钉当前版本,后续技能内容变更不会自动影响该 Agent(需重新发布才跟随)
        install.setPinnedVersion(skill.getVersion() == null ? 1 : skill.getVersion());
        return installMapper.insert(install) > 0;
    }

    @Override
    public Boolean uninstallFromAgent(Long skillId, Long agentId) {
        // 关联表语义:卸载即断关系,物理删除
        return installMapper.physicalUnbind(skillId, agentId) > 0;
    }

    @Override
    public List<AgentSkill> listInstalledByAgent(Long agentId) {
        List<AgentSkillInstall> installs = installMapper.selectList(
            new LambdaQueryWrapper<AgentSkillInstall>()
                .select(AgentSkillInstall::getSkillId, AgentSkillInstall::getPinnedVersion)
                .eq(AgentSkillInstall::getAgentId, agentId)
                .orderByDesc(AgentSkillInstall::getId));
        if (installs.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> pins = new LinkedHashMap<>();
        for (AgentSkillInstall install : installs) {
            pins.putIfAbsent(install.getSkillId(), install.getPinnedVersion());
        }
        List<AgentSkill> skills = skillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
            .in(AgentSkill::getId, pins.keySet())
            .eq(AgentSkill::getStatus, "0"));
        List<AgentSkill> result = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            // P1-B:按安装时钉住的版本返回内容,目录摘要与 load_skill 手册口径一致
            result.add(skillVersionService.applyPinned(skill, pins.get(skill.getId())));
        }
        return result;
    }

    @Override
    public AgentSkill getInstalledByName(Long agentId, String name) {
        List<AgentSkill> installed = listInstalledByAgent(agentId);
        return installed.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<SkillInstallTargetVo> listInstallTargets(Long skillId) {
        AgentSkill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new ServiceException("技能不存在");
        }
        resourceGuard.assertReadable(skill.getWorkspaceId());

        Map<Long, LocalDateTime> installedAt = new HashMap<>();
        for (AgentSkillInstall install : installMapper.selectList(
            new LambdaQueryWrapper<AgentSkillInstall>()
                .select(AgentSkillInstall::getAgentId, AgentSkillInstall::getCreateTime)
                .eq(AgentSkillInstall::getSkillId, skillId))) {
            installedAt.putIfAbsent(install.getAgentId(), install.getCreateTime());
        }

        LambdaQueryWrapper<AgentInfo> wrapper = new LambdaQueryWrapper<AgentInfo>()
            .select(AgentInfo::getId, AgentInfo::getName, AgentInfo::getStatus, AgentInfo::getWorkspaceId)
            .eq(AgentInfo::getStatus, "1")
            .orderByDesc(AgentInfo::getId);
        resourceGuard.applyVisible(wrapper, AgentInfo::getWorkspaceId);

        List<SkillInstallTargetVo> rows = new ArrayList<>();
        for (AgentInfo agent : agentMapper.selectList(wrapper)) {
            SkillInstallTargetVo vo = new SkillInstallTargetVo();
            vo.setAgentId(agent.getId());
            vo.setAgentName(agent.getName());
            vo.setAgentStatus(agent.getStatus());
            vo.setWorkspaceId(agent.getWorkspaceId());
            LocalDateTime at = installedAt.get(agent.getId());
            vo.setInstalled(at != null);
            vo.setInstallTime(toDate(at));
            rows.add(vo);
        }
        return rows;
    }

    @Override
    public List<AgentSkillBindVo> listInstalledSkills(Long agentId) {
        List<AgentSkillInstall> installs = installMapper.selectList(
            new LambdaQueryWrapper<AgentSkillInstall>()
                .select(AgentSkillInstall::getSkillId, AgentSkillInstall::getCreateTime,
                    AgentSkillInstall::getPinnedVersion)
                .eq(AgentSkillInstall::getAgentId, agentId)
                .orderByDesc(AgentSkillInstall::getId));
        if (installs.isEmpty()) {
            return List.of();
        }
        Map<Long, AgentSkillInstall> bySkill = new LinkedHashMap<>();
        for (AgentSkillInstall install : installs) {
            bySkill.putIfAbsent(install.getSkillId(), install);
        }
        List<AgentSkill> skills = skillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
            .select(AgentSkill::getId, AgentSkill::getName, AgentSkill::getDescription,
                AgentSkill::getVersion, AgentSkill::getStatus, AgentSkill::getWorkspaceId)
            .in(AgentSkill::getId, bySkill.keySet()));
        Map<Long, AgentSkill> byId = new HashMap<>();
        for (AgentSkill skill : skills) {
            byId.put(skill.getId(), skill);
        }
        List<AgentSkillBindVo> rows = new ArrayList<>();
        for (Map.Entry<Long, AgentSkillInstall> entry : bySkill.entrySet()) {
            Long sid = entry.getKey();
            AgentSkillInstall install = entry.getValue();
            AgentSkill skill = byId.get(sid);
            if (skill == null) {
                // 技能已被删除,绑定成为孤儿:不在列表展示,交由技能管理的级联解绑清理
                log.warn("[Skill] 应用 {} 存在孤儿技能绑定 skillId={}", agentId, sid);
                continue;
            }
            Integer pinned = install.getPinnedVersion();
            AgentSkillBindVo vo = new AgentSkillBindVo();
            vo.setSkillId(sid);
            vo.setName(skill.getName());
            vo.setDescription(StringUtils.defaultString(skill.getDescription()));
            // 显示实际生效版本(被钉住时即钉住的版本),便于看出技能是否已升级但未重新发布
            vo.setVersion(pinned != null ? pinned : (skill.getVersion() == null ? 1 : skill.getVersion()));
            vo.setPinnedVersion(pinned);
            // 运维需要看到停用技能并手动卸载,因此这里不按状态过滤
            vo.setStatus(skill.getStatus());
            vo.setWorkspaceId(skill.getWorkspaceId());
            vo.setInstallTime(toDate(install.getCreateTime()));
            rows.add(vo);
        }
        return rows;
    }

    private static Date toDate(LocalDateTime at) {
        return at == null ? null : Date.from(at.atZone(ZoneId.systemDefault()).toInstant());
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
