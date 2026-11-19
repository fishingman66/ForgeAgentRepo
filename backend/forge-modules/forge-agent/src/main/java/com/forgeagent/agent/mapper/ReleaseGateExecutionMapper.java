package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.ReleaseGateExecution;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

public interface ReleaseGateExecutionMapper extends BaseMapperPlus<ReleaseGateExecution, ReleaseGateExecution> {

    @org.apache.ibatis.annotations.Select("""
            SELECT id,agent_id,target_version,status,reason,waived,checks_json,create_time
            FROM release_gate_execution WHERE agent_id=#{agentId} ORDER BY id DESC LIMIT 50
            """)
    java.util.List<com.forgeagent.agent.domain.vo.ReleaseGateExecutionVo> listByAgent(Long agentId);
}
