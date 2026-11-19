package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentRun;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

public interface AgentRunMapper extends BaseMapperPlus<AgentRun, AgentRun> {

    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*) AS total,
              SUM(CASE WHEN status IN ('failed','cancelled') THEN 1 ELSE 0 END) AS failed
            FROM agent_run WHERE agent_id=#{agentId} AND create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """)
    java.util.List<java.util.Map<String, Object>> recent7dMetrics(Long agentId);
    @org.apache.ibatis.annotations.Select("SELECT user_id FROM agent_run WHERE run_id=#{runId}")
    Long selectOwner(String runId);

    @org.apache.ibatis.annotations.Select("SELECT visitor_id,channel_code FROM agent_run WHERE run_id=#{runId} LIMIT 1")
    com.forgeagent.agent.domain.vo.AgentRunVo selectVisitorChannel(String runId);
}
