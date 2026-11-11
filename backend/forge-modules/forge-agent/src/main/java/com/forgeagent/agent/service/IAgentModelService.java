package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentModelBo;
import com.forgeagent.agentcore.domain.vo.AgentModelVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 模型配置 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentModelService {

    PageResult<AgentModelVo> queryPageList(AgentModelBo bo, PageQuery pageQuery);

    AgentModelVo queryById(Long id);

    Boolean insertByBo(AgentModelBo bo);

    Boolean updateByBo(AgentModelBo bo);

    Boolean changeStatus(Long id, String status);

    Boolean deleteByIds(List<Long> ids);
}
