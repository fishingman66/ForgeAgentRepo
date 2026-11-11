package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentInfoBo;
import com.forgeagent.agentcore.domain.vo.AgentInfoVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * Agent 定义 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentInfoService {

    PageResult<AgentInfoVo> queryPageList(AgentInfoBo bo, PageQuery pageQuery);

    AgentInfoVo queryById(Long id);

    /**
     * 新增 Agent
     *
     * @return 新增记录ID(失败返回 null)
     */
    Long insertByBo(AgentInfoBo bo);

    Boolean updateByBo(AgentInfoBo bo);

    Boolean changeStatus(Long id, String status);

    Boolean deleteByIds(List<Long> ids);

    /**
     * 克隆 Agent:复制全部配置到同空间下的新 Agent,状态置为草稿
     *
     * @param id 原 Agent ID
     * @return 新 Agent ID
     */
    Long cloneById(Long id);

    List<AgentInfoVo> listPublished();

    /**
     * 回滚 Agent 配置到指定版本(生成新版本快照,不覆盖历史)
     *
     * @param agentId   AgentID
     * @param versionNo 目标版本号
     */
    void rollbackVersion(Long agentId, int versionNo);
}
