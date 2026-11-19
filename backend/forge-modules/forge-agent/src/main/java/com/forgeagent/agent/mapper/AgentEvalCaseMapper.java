package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentEvalCase;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 评测样本 Mapper(评测域 JdbcTemplate 治理迁移)。 */
public interface AgentEvalCaseMapper extends BaseMapperPlus<AgentEvalCase, AgentEvalCase> {

    @Select("""
            SELECT id,dataset_id,question,expected_keywords,reference_answer,expected_refusal,
                   source,source_ref_id,review_status,create_time
            FROM agent_eval_case WHERE dataset_id=#{datasetId} ORDER BY id
            """)
    List<com.forgeagent.agent.domain.vo.AgentEvalCaseVo> listByDataset(Long datasetId);

    /** D2:只取已确认样本(review_status NULL 或 '1'),待确认回流样本不参与评测。 */
    @Select("""
            SELECT id,question,expected_keywords,reference_answer,expected_refusal FROM agent_eval_case
            WHERE dataset_id=#{datasetId} AND (review_status IS NULL OR review_status='1')
            ORDER BY id LIMIT #{limit}
            """)
    List<Map<String, Object>> selectApproved(Long datasetId, int limit);

    /** 回流用例列表(带 dataset 的 workspace 归属校验,source 非空=回流样本)。 */
    @Select({"<script>", """
            SELECT c.id,c.dataset_id,c.question,c.reference_answer,c.source,c.source_ref_id,
                   c.review_status,c.create_time
            FROM agent_eval_case c
            JOIN agent_eval_dataset d ON d.id = c.dataset_id AND d.del_flag = 0
            WHERE d.workspace_id=#{workspaceId} AND c.source IS NOT NULL
            <if test="reviewStatus != null and reviewStatus != ''"> AND c.review_status=#{reviewStatus} </if>
            ORDER BY c.id DESC LIMIT #{limit}
            """, "</script>"})
    List<Map<String, Object>> listFeedbackCases(
            @org.apache.ibatis.annotations.Param("workspaceId") Long workspaceId,
            @org.apache.ibatis.annotations.Param("reviewStatus") String reviewStatus,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /** 归属校验:caseId 必须属于指定 workspace 的 dataset。 */
    @Select("""
            SELECT c.id FROM agent_eval_case c
            JOIN agent_eval_dataset d ON d.id = c.dataset_id AND d.del_flag = 0
            WHERE c.id=#{caseId} AND d.workspace_id=#{workspaceId}
            """)
    List<Map<String, Object>> checkOwnership(
            @org.apache.ibatis.annotations.Param("caseId") Long caseId,
            @org.apache.ibatis.annotations.Param("workspaceId") Long workspaceId);

    /** 待确认数量(回流样本 + workspace 归属)。 */
    @Select("""
            SELECT COUNT(*) AS cnt FROM agent_eval_case c
            JOIN agent_eval_dataset d ON d.id = c.dataset_id AND d.del_flag = 0
            WHERE d.workspace_id=#{workspaceId} AND c.source IS NOT NULL AND c.review_status=#{pendingStatus}
            """)
    long countPending(@org.apache.ibatis.annotations.Param("workspaceId") Long workspaceId,
                      @org.apache.ibatis.annotations.Param("pendingStatus") String pendingStatus);
}
