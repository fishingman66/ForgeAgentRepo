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

    /**
     * C3-2:保存前连通性测试(BYO-key 自助)。
     *
     * <p>入参为页面当前表单:apiKey 留空或含掩码时回退到库内密文,便于"只改地址"也能测。</p>
     */
    com.forgeagent.agent.domain.vo.AgentModelTestVo testConnection(AgentModelBo bo);
}
