package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.domain.bo.AgentSkillBo;
import com.forgeagent.agent.domain.vo.AgentSkillBindVo;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.agent.domain.vo.SkillInstallTargetVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 技能包 Service(08方案 / A1)
 *
 * @author ForgeAgent
 */
public interface IAgentSkillService {

    /** 分页查询技能 */
    PageResult<AgentSkillVo> queryPageList(AgentSkillBo bo, PageQuery pageQuery);

    /** 技能详情 */
    AgentSkillVo queryById(Long id);

    /** 新增技能 */
    Boolean insertByBo(AgentSkillBo bo);

    /** 修改技能(内容变更版本自增) */
    Boolean updateByBo(AgentSkillBo bo);

    /** 启用/停用技能 */
    Boolean changeStatus(Long id, String status);

    /** 删除技能(级联解绑全部安装) */
    Boolean deleteByIds(List<Long> ids);

    /** 安装技能到 Agent(幂等) */
    Boolean installToAgent(Long skillId, Long agentId);

    /** 从 Agent 卸载技能 */
    Boolean uninstallFromAgent(Long skillId, Long agentId);

    /** 查询某 Agent 已安装的正常状态技能(装配用,含平台共享) */
    List<AgentSkill> listInstalledByAgent(Long agentId);

    /** 按名称取技能(load_skill 工具按需加载用,仅限已安装且正常状态) */
    AgentSkill getInstalledByName(Long agentId, String name);

    /** 技能视角:全部已发布应用 + 本技能的安装态(管理页安装/卸载用) */
    List<SkillInstallTargetVo> listInstallTargets(Long skillId);

    /** 应用视角:某 Agent 已装技能(含已停用,便于运营卸载) */
    List<AgentSkillBindVo> listInstalledSkills(Long agentId);
}
