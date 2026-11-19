package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 技能包Mapper接口(08方案 / A1)
 *
 * @author ForgeAgent
 */
public interface AgentSkillMapper extends BaseMapperPlus<AgentSkill, AgentSkillVo> {

    /** 已安装但停用/已删的技能名(门禁基础检查用,走 idx_skill_agent_agent)。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT s.name FROM agent_skill_install i
            JOIN agent_skill s ON s.id = i.skill_id
            WHERE i.agent_id=#{agentId} AND i.del_flag=0 AND s.del_flag=0 AND s.status <> '0'
            ORDER BY s.id
            """)
    java.util.List<java.util.Map<String, Object>> disabledSkillNames(Long agentId);
}
