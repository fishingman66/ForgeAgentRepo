package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentVersion;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * agent_version Mapper(治理域迁移)。
 */
public interface AgentVersionMapper extends BaseMapperPlus<AgentVersion, AgentVersion> {

    @org.apache.ibatis.annotations.Select("""
            SELECT COALESCE(MAX(version_no),0)+1 FROM agent_version WHERE agent_id=#{agentId}
            """)
    Integer nextVersionNo(Long agentId);

    @org.apache.ibatis.annotations.Select("""
            SELECT id,agent_id,version_no,config_hash,remark,source,create_by,create_time
            FROM agent_version WHERE agent_id=#{agentId} ORDER BY version_no DESC
            """)
    java.util.List<com.forgeagent.agent.domain.vo.AgentVersionVo> listVersions(Long agentId);
}
