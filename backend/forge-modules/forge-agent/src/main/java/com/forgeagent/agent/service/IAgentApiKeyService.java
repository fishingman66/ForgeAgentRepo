package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentApiKeyBo;
import com.forgeagent.agent.domain.vo.AgentApiKeyCreateVo;
import com.forgeagent.agent.domain.vo.AgentApiKeyVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

/**
 * 空间 API Key Service
 *
 * @author ForgeAgent
 */
public interface IAgentApiKeyService {

    /**
     * 分页查询(列表脱敏,空间成员可见)
     */
    PageResult<AgentApiKeyVo> queryPageList(AgentApiKeyBo bo, PageQuery pageQuery);

    /**
     * 创建 Key(仅空间管理员,明文仅此一次返回)
     */
    AgentApiKeyCreateVo createByBo(AgentApiKeyBo bo);

    /**
     * 吊销 Key(仅空间管理员,吊销后不可恢复)
     */
    Boolean revoke(Long id);
}
