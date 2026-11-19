package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentMcpClientBo;
import com.forgeagent.agent.domain.vo.AgentMcpClientTokenVo;
import com.forgeagent.agent.domain.vo.AgentMcpClientVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 对外 MCP 客户端 Service(08方案 / A5 增强)
 *
 * @author ForgeAgent
 */
public interface IAgentMcpClientService {

    /** 分页查询客户端(不含令牌) */
    PageResult<AgentMcpClientVo> queryPageList(AgentMcpClientBo bo, PageQuery pageQuery);

    /** 客户端详情 */
    AgentMcpClientVo queryById(Long id);

    /** 新建客户端并下发一次性明文令牌 */
    AgentMcpClientTokenVo createClient(AgentMcpClientBo bo);

    /** 修改客户端(名称/空间/授权域/过期时间/备注) */
    Boolean updateByBo(AgentMcpClientBo bo);

    /** 启用/停用 */
    Boolean changeStatus(Long id, String enabled);

    /** 轮转令牌:旧令牌立即失效,返回新明文 */
    AgentMcpClientTokenVo rotateToken(Long id);

    /** 删除客户端 */
    Boolean deleteByIds(List<Long> ids);
}
