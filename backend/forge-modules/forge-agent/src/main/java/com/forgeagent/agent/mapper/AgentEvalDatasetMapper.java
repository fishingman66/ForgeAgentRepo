package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.AgentEvalDataset;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 评测集 Mapper(评测域 JdbcTemplate 治理迁移)。 */
public interface AgentEvalDatasetMapper extends BaseMapperPlus<AgentEvalDataset, AgentEvalDataset> {

    /** 评测集列表 + 样本数(count 子查询保留原生 SQL,返回列别名维持下划线契约)。 */
    @Select("""
            SELECT d.id,d.workspace_id,d.agent_id,d.name,d.description,d.create_time,
              (SELECT COUNT(*) FROM agent_eval_case c WHERE c.dataset_id=d.id) AS case_count
            FROM agent_eval_dataset d WHERE d.del_flag=0 AND (d.workspace_id=#{workspaceId} OR d.workspace_id=0)
            ORDER BY d.id DESC
            """)
    List<com.forgeagent.agent.domain.vo.AgentEvalDatasetVo> listWithCaseCount(Long workspaceId);

    /** 按 workspace + agent + name 定位回流评测集(agentId 为 null 时匹配 IS NULL)。 */
    @Select({"<script>", """
            SELECT id FROM agent_eval_dataset
            WHERE workspace_id=#{workspaceId} AND name=#{name} AND del_flag=0
            <if test="agentId != null"> AND agent_id=#{agentId} </if>
            <if test="agentId == null"> AND agent_id IS NULL </if>
            ORDER BY id LIMIT 1
            """, "</script>"})
    List<Long> findId(@org.apache.ibatis.annotations.Param("workspaceId") Long workspaceId,
                      @org.apache.ibatis.annotations.Param("agentId") Long agentId,
                      @org.apache.ibatis.annotations.Param("name") String name);
}
