package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentPromptTemplate;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * agent_prompt_template Mapper(治理域迁移)。
 */
public interface AgentPromptTemplateMapper extends BaseMapperPlus<AgentPromptTemplate, AgentPromptTemplate> {

    @org.apache.ibatis.annotations.Select("""
            SELECT id,workspace_id,name,description,category,stable_version_id,status,create_time
            FROM agent_prompt_template WHERE del_flag=0 AND (workspace_id=#{workspaceId} OR workspace_id=0)
            ORDER BY id DESC
            """)
    java.util.List<com.forgeagent.agent.domain.vo.AgentPromptTemplateVo> listTemplates(Long workspaceId);

    @org.apache.ibatis.annotations.Select("""
            SELECT stable_version_id FROM agent_prompt_template WHERE id=#{id} AND del_flag=0
            """)
    java.util.List<Long> stableVersionId(Long id);
}
