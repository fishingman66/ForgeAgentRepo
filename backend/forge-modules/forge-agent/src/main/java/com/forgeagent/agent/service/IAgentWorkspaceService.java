package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentWorkspaceBo;
import com.forgeagent.agent.domain.vo.AgentWorkspaceVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 工作空间 Service
 *
 * @author ForgeAgent
 */
public interface IAgentWorkspaceService {

    /**
     * 我加入的工作空间列表(含我的角色)
     */
    List<AgentWorkspaceVo> myWorkspaces(String keyword);

    /**
     * 分页查询全部空间(平台管理视角)
     */
    PageResult<AgentWorkspaceVo> queryPageList(AgentWorkspaceBo bo, PageQuery pageQuery);

    /**
     * 空间详情
     */
    AgentWorkspaceVo queryById(Long id);

    /**
     * 创建空间(创建者自动成为空间管理员)
     */
    Boolean insertByBo(AgentWorkspaceBo bo);

    /**
     * 编辑空间(需空间管理员)
     */
    Boolean updateByBo(AgentWorkspaceBo bo);

    /**
     * 启用/停用空间(需空间管理员)
     */
    Boolean changeStatus(Long id, String status);

    /**
     * 删除空间(需空间管理员,级联清理成员)
     */
    Boolean deleteByIds(List<Long> ids);
}
