package com.forgeagent.rag.mapper;

import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.domain.vo.AgentKnowledgeDocumentVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
/**
 * 知识库文档 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentKnowledgeDocumentMapper extends BaseMapperPlus<AgentKnowledgeDocument, AgentKnowledgeDocumentVo> {

    /**
     * 统计各知识库"配置过期"的可用文档数(R2-5):前端据此提示待差异重建规模并分流重建入口
     *
     * @param knowledgeIds 知识库ID列表
     */
    @Select("""
        <script>
        SELECT knowledge_id AS knowledgeId, COUNT(*) AS cnt
        FROM agent_knowledge_document
        WHERE config_stale = 1 AND del_flag = 0
          AND knowledge_id IN
        <foreach collection='knowledgeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        GROUP BY knowledge_id
        </script>
        """)
    List<Map<String, Object>> countStaleByKnowledgeIds(@Param("knowledgeIds") List<Long> knowledgeIds);
}
