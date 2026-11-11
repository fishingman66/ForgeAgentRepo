package com.forgeagent.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import com.forgeagent.agent.domain.AgentTokenUsageDaily;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * Token 用量日汇总 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentTokenUsageDailyMapper extends BaseMapperPlus<AgentTokenUsageDaily, AgentTokenUsageDaily> {

    /**
     * 原子 upsert 日汇总:依赖 uk_dim 唯一键,命中则累加,未命中则插入;
     * 替代 select-then-insert 读-改-写,避免并发下重复行/丢更新
     */
    @Insert("INSERT INTO agent_token_usage_daily "
        + "(stat_date, workspace_id, agent_id, model_name, user_id, source, estimated, "
        + "prompt_tokens, completion_tokens, total_tokens, call_count, create_time, update_time) "
        + "VALUES (#{statDate}, #{workspaceId}, #{agentId}, #{modelName}, #{userId}, #{source}, #{estimated}, "
        + "#{promptTokens}, #{completionTokens}, #{totalTokens}, 1, NOW(), NOW()) "
        + "ON DUPLICATE KEY UPDATE "
        + "prompt_tokens = prompt_tokens + VALUES(prompt_tokens), "
        + "completion_tokens = completion_tokens + VALUES(completion_tokens), "
        + "total_tokens = total_tokens + VALUES(total_tokens), "
        + "call_count = call_count + 1, update_time = NOW()")
    int upsertDaily(AgentTokenUsageDaily daily);

}
