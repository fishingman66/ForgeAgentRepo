package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentEvalRun;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 评测运行 Mapper(评测域 JdbcTemplate 治理迁移)。 */
public interface AgentEvalRunMapper extends BaseMapperPlus<AgentEvalRun, AgentEvalRun> {

    @Select("""
            SELECT id,case_id,question,answer,score,judge_score,judge_reason,latency_ms,error_message
            FROM agent_eval_case_result WHERE run_id=#{runId} ORDER BY id
            """)
    List<com.forgeagent.agent.domain.vo.AgentEvalCaseResultVo> selectResults(Long runId);

    @Select("""
            SELECT id,dataset_id,agent_id,status,total_cases,finished_cases,avg_score,
              tool_success_rate,refusal_rate,p95_latency_ms,judge_enabled,create_time,finish_time
            FROM agent_eval_run WHERE agent_id=#{agentId} ORDER BY id DESC LIMIT 50
            """)
    List<com.forgeagent.agent.domain.vo.AgentEvalRunVo> listByAgent(Long agentId);

    @org.apache.ibatis.annotations.Select("""
            SELECT avg_score,tool_success_rate,refusal_rate,p95_latency_ms,config_hash
            FROM agent_eval_run WHERE agent_id=#{agentId} AND status='success' ORDER BY id DESC LIMIT 1
            """)
    java.util.List<java.util.Map<String, Object>> latestSuccess(Long agentId);
}
