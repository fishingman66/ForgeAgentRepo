package com.forgeagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.agent.domain.AgentWorkspaceInvite;

/** F2 邀请令牌 Mapper(单次消费靠条件更新,见 Service)。 */
public interface AgentWorkspaceInviteMapper extends BaseMapper<AgentWorkspaceInvite> {
}
