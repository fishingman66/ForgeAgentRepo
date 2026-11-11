package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentMcpServerBo;
import com.forgeagent.agent.domain.vo.AgentMcpServerVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * MCP 服务器注册 Service
 *
 * @author ForgeAgent
 */
public interface IAgentMcpServerService {

    /**
     * 分页查询
     */
    PageResult<AgentMcpServerVo> queryPageList(AgentMcpServerBo bo, PageQuery pageQuery);

    /**
     * 详情
     */
    AgentMcpServerVo queryById(Long id);

    /**
     * 新增
     */
    Boolean insertByBo(AgentMcpServerBo bo);

    /**
     * 修改
     */
    Boolean updateByBo(AgentMcpServerBo bo);

    /**
     * 启用/停用
     */
    Boolean changeStatus(Long id, String status);

    /**
     * 删除
     */
    Boolean deleteByIds(List<Long> ids);

    /**
     * 连接测试:真实建立 MCP 连接并列出工具,返回工具名列表
     */
    List<String> testConnection(Long id);
}
