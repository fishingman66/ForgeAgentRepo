package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.ReleaseGatePolicy;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

public interface ReleaseGatePolicyMapper extends BaseMapperPlus<ReleaseGatePolicy, ReleaseGatePolicy> {

    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM release_gate_policy
            WHERE resource_type='agent' AND enabled='1'
              AND (workspace_id=#{workspaceId} OR workspace_id IS NULL)
            ORDER BY workspace_id IS NULL ASC LIMIT 1
            """)
    java.util.List<java.util.Map<String, Object>> selectApplicable(Long workspaceId);
}
