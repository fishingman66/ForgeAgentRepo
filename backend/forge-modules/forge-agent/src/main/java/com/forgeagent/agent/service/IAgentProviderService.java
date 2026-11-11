package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentProviderBo;
import com.forgeagent.agent.domain.vo.AgentProviderVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 模型供应商配置 Service
 *
 * @author ForgeAgent
 */
public interface IAgentProviderService {

    /**
     * 分页查询供应商
     */
    PageResult<AgentProviderVo> queryPageList(AgentProviderBo bo, PageQuery pageQuery);

    /**
     * 供应商详情
     */
    AgentProviderVo queryById(Long id);

    /**
     * 全部供应商(下拉用)
     */
    List<AgentProviderVo> listAll();

    /**
     * 新增供应商(仅平台超管)
     */
    Boolean insertByBo(AgentProviderBo bo);

    /**
     * 修改供应商(仅平台超管)
     */
    Boolean updateByBo(AgentProviderBo bo);

    /**
     * 启用/停用供应商(仅平台超管)
     */
    Boolean changeStatus(Long id, String status);

    /**
     * 删除供应商(仅平台超管,被模型引用时禁止)
     */
    Boolean deleteByIds(List<Long> ids);
}
