package com.forgeagent.agent.service;

import jakarta.servlet.http.HttpServletResponse;
import com.forgeagent.agent.domain.bo.AgentTokenUsageBo;
import com.forgeagent.agent.domain.vo.AgentSessionUsageVo;
import com.forgeagent.agent.domain.vo.AgentTokenUsageVo;
import com.forgeagent.agent.domain.vo.AgentUsageReportVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

/**
 * Token 用量统计 Service
 *
 * @author ForgeAgent
 */
public interface IAgentTokenUsageService {

    /**
     * 用量统计报表(指标卡/按日趋势/排行)
     */
    AgentUsageReportVo report(AgentTokenUsageBo bo);

    /**
     * 明细分页查询(受可见范围约束)
     */
    PageResult<AgentTokenUsageVo> queryPageList(AgentTokenUsageBo bo, PageQuery pageQuery);

    /**
     * 会话内消耗汇总
     */
    AgentSessionUsageVo sessionUsage(Long sessionId);

    /**
     * 会话用量实时反馈(UX-03):累计/本轮/上下文剩余/成本估算,归会话本人或空间管理员可见
     */
    com.forgeagent.agent.domain.vo.AgentSessionUsageBarVo sessionUsageBar(Long sessionId);

    /**
     * 按筛选条件导出明细 Excel
     */
    void export(AgentTokenUsageBo bo, HttpServletResponse response);
}
