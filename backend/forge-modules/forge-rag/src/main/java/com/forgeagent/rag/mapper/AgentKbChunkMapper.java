package com.forgeagent.rag.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.vo.AgentKbChunkVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import java.util.List;
import java.util.Map;
/**
 * 知识库分块 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentKbChunkMapper extends BaseMapperPlus<AgentKbChunk, AgentKbChunkVo> {

    /**
     * 全文关键词检索(MySQL FULLTEXT ngram)
     *
     * <p>join 文档表两道过滤:已删除文档不参与;只认文档当前生效代次的分块
     * (重建期间新代先写库、指针后切,这里必须排除新代,否则会召回半成品内容)。</p>
     *
     * @param knowledgeId 知识库ID
     * @param query       查询文本
     * @param topK        返回条数
     * @return 命中列表(key=id/document_id/chunk_index/content/score)
     */
    @Select("""
        SELECT c.id, c.document_id, c.chunk_index, c.content,
               MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
        FROM agent_kb_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id AND d.del_flag = 0
        WHERE c.knowledge_id = #{knowledgeId}
          AND c.enabled = 1
          AND c.chunk_role = 'c'
          AND c.generation = d.active_generation
          AND MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
        ORDER BY score DESC, c.id ASC
        LIMIT #{topK}
        """)
    List<Map<String, Object>> searchByKeyword(@Param("knowledgeId") Long knowledgeId,
                                               @Param("query") String query,
                                               @Param("topK") int topK);

    /**
     * 批量累计分块命中次数
     *
     * @param ids 命中的分块ID列表
     */
    @org.apache.ibatis.annotations.Update("""
        <script>
        UPDATE agent_kb_chunk SET hit_count = hit_count + 1 WHERE id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    void incrementHitCount(@Param("ids") List<Long> ids);

    /**
     * 找出"代次落后于文档生效代次"的遗留分块ID(重建成功但清理中断时由补偿扫描兜底)
     *
     * @param limit 单批上限
     */
    @Select("""
        SELECT c.id FROM agent_kb_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id AND d.del_flag = 0
        WHERE c.generation < d.active_generation
        LIMIT #{limit}
        """)
    List<Long> selectStaleGenerationChunkIds(@Param("limit") int limit);
}
