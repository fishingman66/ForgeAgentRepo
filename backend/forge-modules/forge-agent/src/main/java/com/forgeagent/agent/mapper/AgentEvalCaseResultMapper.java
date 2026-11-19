package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentEvalCaseResult;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Select;

/** 评测样本结果 Mapper(评测域 JdbcTemplate 治理迁移)。 */
public interface AgentEvalCaseResultMapper extends BaseMapperPlus<AgentEvalCaseResult, AgentEvalCaseResult> {

    @Select("SELECT COUNT(*) FROM agent_eval_case_result WHERE run_id=#{runId} AND error_message IS NOT NULL")
    int countErrors(Long runId);
}
