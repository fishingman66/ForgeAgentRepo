package com.forgeagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * P3 / C5:技能市场(只读发现 + 一键绑定)。
 *
 * <p><b>范围收敛的依据</b>:技能已有「平台共享」概念({@code workspace_id IS NULL}),
 * 对全平台可见,所以"市场"真正缺的不是复制/审核,而是<b>发现入口</b>。
 * 因此本服务只做三件事:列出共享技能、看详情、绑定到自己的 Agent。
 * 用户发布与审核流不做(那需要新表 + 审核台,是完整市场 80% 的工作量,
 * 且 docs/13 已把它标为"选做、非对标刚需")。</p>
 *
 * <p><b>隔离红线</b>:共享技能的跨空间可见性在这里用<b>显式查询</b>实现,
 * 刻意不改 {@code ResourceAccessGuard.applyVisible} 的默认语义——否则所有资源域的
 * 可见性口径都会被动放开,是比"看不到技能市场"严重得多的回归。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMarketService {

    private final AgentSkillMapper skillMapper;
    private final IAgentSkillService skillService;

    /**
     * 浏览共享技能。只出启用中的平台共享项,普通用户无需管理权限。
     */
    public PageResult<AgentSkillVo> browse(String keyword, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentSkill> wrapper = new LambdaQueryWrapper<AgentSkill>()
            .isNull(AgentSkill::getWorkspaceId)
            .eq(AgentSkill::getStatus, "0")
            .like(StringUtils.isNotBlank(keyword), AgentSkill::getName, keyword)
            .orderByDesc(AgentSkill::getId);
        Page<AgentSkillVo> page = skillMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 共享技能详情。非共享项按"不存在"处理(不泄露私有技能是否存在)。
     */
    public AgentSkillVo detail(Long skillId) {
        AgentSkill skill = requireShared(skillId);
        return MapstructUtils.convert(skill, AgentSkillVo.class);
    }

    /**
     * 把共享技能绑定到指定 Agent。
     *
     * <p>Agent 归属校验在 {@link IAgentSkillService#installToAgent} 内完成
     * (写校验:私有空间需开发者权限,共享 Agent 仅超管),这里只额外确认技能是共享的——
     * 市场入口不应该成为"跨空间绑定别人私有技能"的旁路。</p>
     */
    public boolean install(Long skillId, Long agentId) {
        requireShared(skillId);
        return skillService.installToAgent(skillId, agentId);
    }

    private AgentSkill requireShared(Long skillId) {
        AgentSkill skill = skillMapper.selectById(skillId);
        if (skill == null || skill.getWorkspaceId() != null) {
            throw new ServiceException("技能不存在或未公开");
        }
        if (!"0".equals(skill.getStatus())) {
            throw new ServiceException("技能已停用");
        }
        return skill;
    }
}
