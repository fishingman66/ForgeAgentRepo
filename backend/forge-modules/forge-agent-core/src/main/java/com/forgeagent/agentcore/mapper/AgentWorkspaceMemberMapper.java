package com.forgeagent.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.domain.vo.AgentWorkspaceMemberVo;

import java.util.List;

/**
 * 工作空间成员 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentWorkspaceMemberMapper extends BaseMapper<AgentWorkspaceMember> {

    /**
     * 分页查询空间成员(联表带出用户信息)
     *
     * @param page        分页
     * @param workspaceId 空间ID
     * @return 成员分页
     */
    Page<AgentWorkspaceMemberVo> selectMemberPage(@Param("page") Page<AgentWorkspaceMember> page,
                                                  @Param("workspaceId") Long workspaceId);

    /**
     * 查询可加入空间的系统用户(排除已在空间内的成员)
     *
     * @param workspaceId 空间ID
     * @param keyword     账号/昵称关键字
     * @return 候选用户
     */
    List<AgentWorkspaceMemberVo> selectCandidates(@Param("workspaceId") Long workspaceId,
                                                  @Param("keyword") String keyword);
}
