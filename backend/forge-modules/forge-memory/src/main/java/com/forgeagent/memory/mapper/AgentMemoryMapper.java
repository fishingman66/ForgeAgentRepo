package com.forgeagent.memory.mapper;

import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.domain.vo.AgentMemoryVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    /**
     * 08方案 / B3:关键词召回通道(MySQL FULLTEXT ngram)。
     *
     * <p>读范围与 {@code MemoryScope.applyReadScope} 完全一致(userId 非空命中本人 + 平台级,
     * 为空只命中平台级);自定义 SQL 不走 MP 逻辑删除插件,必须手写 del_flag = 0;
     * TTL 口径与 buildMemoryBlock 一致(expire_at 已过期不召回)。</p>
     *
     * <p>注意:MyBatis 只识别「字符串字面量以 &lt;script&gt; 开头」的注解 SQL,
     * 文本块前置换行会让它退化成裸 SQL——沿用 RAG 域 searchByKeyword 的拼接写法。</p>
     *
     * @return 按 FTS 相关度降序的记忆实体(不含 embedding 三列)
     */
    @Select("<script>"
        + """
        SELECT id, agent_id, workspace_id, user_id, content, fact_key, importance, hit_count,
               last_hit_at, expire_at, source_session_id, del_flag, create_time,
               MATCH(content, fact_key) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS fts_score
        FROM agent_memory
        WHERE agent_id = #{agentId}
          AND del_flag = 0
          <choose>
            <when test="userId != null">AND (user_id = #{userId} OR user_id IS NULL)</when>
            <otherwise>AND user_id IS NULL</otherwise>
          </choose>
          AND (expire_at IS NULL OR expire_at > NOW())
          AND MATCH(content, fact_key) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
        ORDER BY fts_score DESC, id DESC
        LIMIT #{topK}
        </script>
        """)
    List<AgentMemory> searchByKeyword(@Param("agentId") Long agentId,
                                      @Param("userId") Long userId,
                                      @Param("query") String query,
                                      @Param("topK") int topK);
}
