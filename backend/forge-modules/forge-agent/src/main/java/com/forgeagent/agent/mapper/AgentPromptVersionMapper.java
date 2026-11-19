package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentPromptVersion;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * agent_prompt_version Mapper(治理域迁移)。
 */
public interface AgentPromptVersionMapper extends BaseMapperPlus<AgentPromptVersion, AgentPromptVersion> {

    @org.apache.ibatis.annotations.Select("""
            SELECT id,template_id,version_no,content_hash,remark,create_by,create_time
            FROM agent_prompt_version WHERE template_id=#{templateId} ORDER BY version_no DESC
            """)
    java.util.List<com.forgeagent.agent.domain.vo.AgentPromptVersionVo> listByTemplate(Long templateId);

    @org.apache.ibatis.annotations.Select("""
            SELECT COALESCE(MAX(version_no),0)+1 FROM agent_prompt_version WHERE template_id=#{templateId}
            """)
    Integer nextVersionNo(Long templateId);
}
