package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentEvalBaseline;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 评测基线 Mapper(评测域 JdbcTemplate 治理迁移)。 */
public interface AgentEvalBaselineMapper extends BaseMapperPlus<AgentEvalBaseline, AgentEvalBaseline> {

    @Select("SELECT * FROM agent_eval_baseline WHERE agent_id=#{agentId} AND active='1' ORDER BY id DESC LIMIT 1")
    List<Map<String, Object>> selectActive(Long agentId);

    @org.apache.ibatis.annotations.Select("""
            SELECT metrics_json FROM agent_eval_baseline
            WHERE agent_id=#{agentId} AND active='1' ORDER BY id DESC LIMIT 1
            """)
    java.util.List<java.util.Map<String, Object>> latestMetrics(Long agentId);
}
