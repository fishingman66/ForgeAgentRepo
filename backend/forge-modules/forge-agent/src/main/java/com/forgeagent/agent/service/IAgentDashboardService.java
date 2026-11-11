package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.vo.DashboardOverviewVo;

/**
 * 运营看板 Service
 *
 * @author ForgeAgent
 */
public interface IAgentDashboardService {

    /**
     * 运营看板概览(按当前用户角色裁剪 section)
     *
     * @return 概览数据
     */
    DashboardOverviewVo overview();
}
