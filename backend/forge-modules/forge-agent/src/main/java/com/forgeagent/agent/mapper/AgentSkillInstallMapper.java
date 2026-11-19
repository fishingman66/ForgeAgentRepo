package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentSkillInstall;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 技能安装绑定Mapper接口(08方案 / A1)
 *
 * <p>关联表不走逻辑删除:{@code del_flag} 非 0 的记录仍占用唯一索引
 * {@code uk_skill_agent(skill_id, agent_id)},卸载后重新安装会直接报
 * Duplicate entry。因此解绑一律物理删除,{@code @TableLogic} 只保证查询侧不见残留。</p>
 *
 * @author ForgeAgent
 */
public interface AgentSkillInstallMapper extends BaseMapperPlus<AgentSkillInstall, AgentSkillInstall> {

    /**
     * 物理解绑单个(技能,应用),同时清理历史逻辑删除残留
     */
    @Delete("DELETE FROM agent_skill_install WHERE skill_id = #{skillId} AND agent_id = #{agentId}")
    int physicalUnbind(@Param("skillId") Long skillId, @Param("agentId") Long agentId);

    /**
     * 技能删除时级联物理解绑
     */
    @Delete("<script>DELETE FROM agent_skill_install WHERE skill_id IN "
        + "<foreach collection='skillIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int physicalUnbindBySkillIds(@Param("skillIds") List<Long> skillIds);
}
