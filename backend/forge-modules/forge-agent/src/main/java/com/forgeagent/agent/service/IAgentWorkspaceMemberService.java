package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentWorkspaceMemberBo;
import com.forgeagent.agentcore.domain.vo.AgentWorkspaceMemberVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 工作空间成员 Service
 *
 * @author ForgeAgent
 */
public interface IAgentWorkspaceMemberService {

    /**
     * 分页查询空间成员
     */
    PageResult<AgentWorkspaceMemberVo> queryPageList(Long workspaceId, PageQuery pageQuery);

    /**
     * 可加入空间的候选系统用户
     */
    List<AgentWorkspaceMemberVo> candidates(Long workspaceId, String keyword);

    /**
     * 添加成员(需空间管理员)
     */
    Boolean addMember(AgentWorkspaceMemberBo bo);

    /**
     * 变更成员角色(需空间管理员;最后一名管理员不可降级)
     */
    Boolean changeRole(AgentWorkspaceMemberBo bo);

    /**
     * 移除成员(需空间管理员;最后一名管理员不可移除)
     */
    Boolean removeByIds(List<Long> ids);
}
