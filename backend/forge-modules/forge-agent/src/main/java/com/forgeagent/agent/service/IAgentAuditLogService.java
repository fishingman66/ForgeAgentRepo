package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentAuditLogBo;
import com.forgeagent.agentcore.domain.vo.AgentAuditLogVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

/**
 * 平台审计日志 Service
 *
 * @author ForgeAgent
 */
public interface IAgentAuditLogService {

    /**
     * 分页查询(超管看全部,空间管理员看本空间)
     */
    PageResult<AgentAuditLogVo> queryPageList(AgentAuditLogBo bo, PageQuery pageQuery);

    /**
     * 日志详情
     */
    AgentAuditLogVo queryById(Long id);
}
