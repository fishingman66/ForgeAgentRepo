package com.forgeagent.memory.mapper;

import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.domain.vo.AgentMemoryVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent 长期记忆Mapper接口
 *
 * @author ForgeAgent
 */
public interface AgentMemoryMapper extends BaseMapperPlus<AgentMemory, AgentMemoryVo> {

    /**
     * 遗忘(L5):按 agent+用户物理删除记忆(含向量三列)。
     * 自定义 SQL 不走 @TableLogic,保证是真删除而不是打删除标记。
     */
    @Delete("DELETE FROM agent_memory WHERE agent_id = #{agentId} AND user_id = #{userId}")
    int physicalDeleteByUser(@Param("agentId") Long agentId, @Param("userId") Long userId);

    /** 遗忘前统计:返回级联删除的量给前端 */
    @Select("SELECT COUNT(*) FROM agent_memory WHERE agent_id = #{agentId} AND user_id = #{userId}")
    long countByUser(@Param("agentId") Long agentId, @Param("userId") Long userId);

    /** 配额(L5):agent_info.memory_quota;未配置返回 null(回落全局默认) */
    @Select("SELECT memory_quota FROM agent_info WHERE id = #{agentId}")
    Integer selectAgentQuota(@Param("agentId") Long agentId);
}
