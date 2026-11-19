package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentSkillVersion;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * agent_skill_version Mapper(治理域迁移)。
 */
public interface AgentSkillVersionMapper extends BaseMapperPlus<AgentSkillVersion, AgentSkillVersion> {

    /** 幂等归档:同 (skill_id, version_no) 覆盖(MySQL ON DUPLICATE KEY,MP 无等价 API 保留原生)。 */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO agent_skill_version
              (skill_id,version_no,name,description,instructions,tool_ids,mcp_ids,
               knowledge_ids,prompt_fragment,content_hash,create_by,create_time)
            VALUES (#{skillId},#{versionNo},#{name},#{description},#{instructions},#{toolIds},#{mcpIds},
                    #{knowledgeIds},#{promptFragment},#{contentHash},#{createBy},NOW())
            ON DUPLICATE KEY UPDATE
              name=VALUES(name), description=VALUES(description), instructions=VALUES(instructions),
              tool_ids=VALUES(tool_ids), mcp_ids=VALUES(mcp_ids),
              knowledge_ids=VALUES(knowledge_ids), prompt_fragment=VALUES(prompt_fragment),
              content_hash=VALUES(content_hash)
            """)
    int archive(@org.apache.ibatis.annotations.Param("skillId") Long skillId,
                @org.apache.ibatis.annotations.Param("versionNo") Integer versionNo,
                @org.apache.ibatis.annotations.Param("name") String name,
                @org.apache.ibatis.annotations.Param("description") String description,
                @org.apache.ibatis.annotations.Param("instructions") String instructions,
                @org.apache.ibatis.annotations.Param("toolIds") String toolIds,
                @org.apache.ibatis.annotations.Param("mcpIds") String mcpIds,
                @org.apache.ibatis.annotations.Param("knowledgeIds") String knowledgeIds,
                @org.apache.ibatis.annotations.Param("promptFragment") String promptFragment,
                @org.apache.ibatis.annotations.Param("contentHash") String contentHash,
                @org.apache.ibatis.annotations.Param("createBy") Long createBy);
}
