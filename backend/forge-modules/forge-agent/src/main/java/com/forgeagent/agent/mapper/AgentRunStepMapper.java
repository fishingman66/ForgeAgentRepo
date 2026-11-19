package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentRunStep;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * agent_run_step Mapper(第 3 批迁移)。
 */
public interface AgentRunStepMapper extends BaseMapperPlus<AgentRunStep, AgentRunStep> {

    @org.apache.ibatis.annotations.Select("SELECT COALESCE(MAX(step_no),0)+1 FROM agent_run_step WHERE run_id=#{runId}")
    Integer nextStepNo(String runId);

    @org.apache.ibatis.annotations.Select("""
            SELECT step_no,step_type,step_name,status,latency_ms,token_usage,payload,create_time
            FROM agent_run_step WHERE run_id=#{runId} ORDER BY step_no,id
            """)
    java.util.List<com.forgeagent.agent.domain.vo.AgentRunStepVo> listSteps(String runId);
}
