package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentToolBo;
import com.forgeagent.agent.domain.vo.AgentToolVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 工具注册 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentToolService {

    PageResult<AgentToolVo> queryPageList(AgentToolBo bo, PageQuery pageQuery);

    AgentToolVo queryById(Long id);

    Boolean insertByBo(AgentToolBo bo);

    Boolean updateByBo(AgentToolBo bo);

    Boolean changeStatus(Long id, String status);

    Boolean deleteByIds(List<Long> ids);
}
