package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentDatasourceExampleBo;
import com.forgeagent.agent.domain.vo.AgentDatasourceExampleVo;

import java.util.List;

/**
 * 问数示例库Service接口
 *
 * @author ForgeAgent
 */
public interface IAgentDatasourceExampleService {

    /**
     * 按数据源查询示例列表(按 sort,id 升序)
     */
    List<AgentDatasourceExampleVo> queryList(Long datasourceId);

    /**
     * 新增示例
     */
    Boolean insertByBo(AgentDatasourceExampleBo bo);

    /**
     * 修改示例
     */
    Boolean updateByBo(AgentDatasourceExampleBo bo);

    /**
     * 删除示例
     */
    Boolean deleteByIds(List<Long> ids);
}
