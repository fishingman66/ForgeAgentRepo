package com.forgeagent.customerservice.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 帮助中心只读 Mapper(B1/F1):直读现有知识库文档表与 FAQ 表。
 *
 * <p>过滤条件下推 SQL——只召回已发布、生效期内、未删除、未入回收站的文档;
 * 不新建 CMS 数据模型,不捞全量再在应用层过滤。响应字段在 Service 层裁剪为白名单。</p>
 */
public interface HelpCenterMapper {

    /** 某空间已发布帮助文档(按知识库列表过滤)。 */
    @Select("""
        <script>
        SELECT d.id AS id, d.file_name AS title, d.locale AS locale,
               LEFT(COALESCE(d.parsed_text,''), 400) AS summary, d.update_time AS updateTime
        FROM agent_knowledge_document d
        JOIN agent_knowledge k ON k.id = d.knowledge_id AND k.workspace_id = #{workspaceId}
        WHERE d.del_flag = 0 AND d.trashed = 0
          AND d.status = '1' AND d.publish_status = 'PUBLISHED'
          AND (d.effective_at IS NULL OR d.effective_at &lt;= NOW())
          AND (d.expires_at IS NULL OR d.expires_at &gt; NOW())
          AND d.knowledge_id IN
          <foreach collection='knowledgeIds' item='kid' open='(' separator=',' close=')'>#{kid}</foreach>
          <if test='locale != null and locale != ""'>
            AND d.locale = #{locale}
          </if>
        ORDER BY d.update_time DESC
        LIMIT 200
        </script>
        """)
    List<Map<String, Object>> listPublishedDocs(@Param("workspaceId") Long workspaceId,
                                                 @Param("knowledgeIds") List<Long> knowledgeIds,
                                                 @Param("locale") String locale);

    /** 单篇文档详情(按 ID + 空间 + 已发布校验)。 */
    @Select("""
        <script>
        SELECT d.id AS id, d.file_name AS title, d.locale AS locale,
               d.parsed_text AS content, d.update_time AS updateTime
        FROM agent_knowledge_document d
        JOIN agent_knowledge k ON k.id = d.knowledge_id AND k.workspace_id = #{workspaceId}
        WHERE d.id = #{docId} AND d.del_flag = 0 AND d.trashed = 0
          AND d.status = '1' AND d.publish_status = 'PUBLISHED'
          AND (d.effective_at IS NULL OR d.effective_at &lt;= NOW())
          AND (d.expires_at IS NULL OR d.expires_at &gt; NOW())
        </script>
        """)
    Map<String, Object> findPublishedDoc(@Param("workspaceId") Long workspaceId, @Param("docId") Long docId);

    /** 某空间已生效 FAQ(帮助中心作为文章展示)。 */
    @Select("""
        SELECT question AS title, answer AS summary, 'faq' AS source, create_time AS updateTime
        FROM agent_cs_knowledge_answer
        WHERE workspace_id = #{workspaceId} AND status = 'ACTIVE'
          AND (effective_from IS NULL OR effective_from &lt;= NOW())
          AND (effective_to IS NULL OR effective_to &gt; NOW())
        ORDER BY create_time DESC LIMIT 200
        """)
    List<Map<String, Object>> listActiveFaqs(@Param("workspaceId") Long workspaceId);
}
