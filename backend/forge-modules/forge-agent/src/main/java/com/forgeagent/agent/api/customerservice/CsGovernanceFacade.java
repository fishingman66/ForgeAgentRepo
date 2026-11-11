package com.forgeagent.agent.api.customerservice;

/**
 * 客服机器人治理契约(S0)。
 *
 * <p>客服模板、话术版本与发布门禁全部挂靠 forge-agent 既有治理体系,
 * 客服模块不另建版本/门禁实现。</p>
 */
public interface CsGovernanceFacade {

    /** 该 Agent 当前是否允许发布(走 ReleaseGateService 默认阈值)。 */
    boolean canPublish(Long agentId);

    /** 当前已发布版本号,无发布记录返回 null。 */
    Long currentPublishedVersion(Long agentId);
}
