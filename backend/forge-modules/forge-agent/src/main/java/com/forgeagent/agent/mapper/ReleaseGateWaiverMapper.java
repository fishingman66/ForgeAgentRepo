package com.forgeagent.agent.mapper;

import com.forgeagent.agent.domain.ReleaseGateWaiver;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * release_gate_waiver Mapper(治理域迁移)。
 */
public interface ReleaseGateWaiverMapper extends BaseMapperPlus<ReleaseGateWaiver, ReleaseGateWaiver> {

    @org.apache.ibatis.annotations.Select("""
            SELECT id,agent_id,reason,status,requested_by,approved_by,expires_at,create_time
            FROM release_gate_waiver WHERE agent_id=#{agentId} ORDER BY id DESC LIMIT 30
            """)
    java.util.List<com.forgeagent.agent.domain.vo.ReleaseGateWaiverVo> listByAgent(Long agentId);

    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*) FROM release_gate_waiver
            WHERE agent_id=#{agentId} AND status='approved' AND expires_at > NOW()
            """)
    int countApprovedActive(Long agentId);
}
