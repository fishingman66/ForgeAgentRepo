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

    /** 快照技能引用(JOIN skill/version,按 skill_id 定序保证 hash 稳定)。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT i.skill_id                              AS id,
                   COALESCE(i.pinned_version, s.version, 1) AS version,
                   s.name                                  AS name,
                   v.content_hash                          AS contentHash
            FROM agent_skill_install i
            JOIN agent_skill s ON s.id = i.skill_id AND s.del_flag = 0
            LEFT JOIN agent_skill_version v
                   ON v.skill_id = i.skill_id
                  AND v.version_no = COALESCE(i.pinned_version, s.version, 1)
            WHERE i.agent_id = #{agentId} AND i.del_flag = 0
            ORDER BY i.skill_id
            """)
    java.util.List<java.util.Map<String, Object>> skillRefsOf(Long agentId);

    /** 发布:把该 Agent 的安装关系钉到技能当前版本。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE agent_skill_install i
            JOIN agent_skill s ON s.id = i.skill_id
            SET i.pinned_version = COALESCE(s.version, 1)
            WHERE i.agent_id = #{agentId} AND i.del_flag = 0
            """)
    int pinCurrentVersions(Long agentId);

    @org.apache.ibatis.annotations.Select("""
            SELECT id,skill_id FROM agent_skill_install WHERE agent_id=#{agentId} AND del_flag=0
            """)
    java.util.List<java.util.Map<String, Object>> currentBindings(Long agentId);

    /** 回滚补插:从技能行带出 workspace_id。 */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO agent_skill_install (skill_id,agent_id,workspace_id,pinned_version,create_time)
            SELECT s.id, #{agentId}, s.workspace_id, #{version}, NOW() FROM agent_skill s
            WHERE s.id=#{skillId} AND s.del_flag=0
            """)
    int insertPinned(@org.apache.ibatis.annotations.Param("agentId") Long agentId,
                     @org.apache.ibatis.annotations.Param("version") Integer version,
                     @org.apache.ibatis.annotations.Param("skillId") Long skillId);
}
