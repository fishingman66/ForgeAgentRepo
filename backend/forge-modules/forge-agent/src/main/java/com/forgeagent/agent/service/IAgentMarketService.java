package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentMarketInstallBo;
import com.forgeagent.agent.domain.bo.AgentMarketListingBo;
import com.forgeagent.agent.domain.bo.AgentMarketPublishBo;
import com.forgeagent.agent.domain.bo.AgentMarketReviewBo;
import com.forgeagent.agent.domain.vo.AgentInstallCheckVo;
import com.forgeagent.agent.domain.vo.AgentMarketInstallVo;
import com.forgeagent.agent.domain.vo.AgentMarketListingVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * Agent 智能体市场 Service
 *
 * @author ForgeAgent
 */
public interface IAgentMarketService {

    /**
     * 市场浏览(仅已上架,支持分类/关键字/排序)
     */
    PageResult<AgentMarketListingVo> browsePage(AgentMarketListingBo bo, PageQuery pageQuery);

    /**
     * 条目详情(已上架对所有人可见,其余仅发布者/超管)
     */
    AgentMarketListingVo detail(Long id);

    /**
     * 我的发布分页
     */
    PageResult<AgentMarketListingVo> myPage(AgentMarketListingBo bo, PageQuery pageQuery);

    /**
     * 提交发布(仅本人已发布的Agent,版本号递增)
     */
    Long publish(AgentMarketPublishBo bo);

    /**
     * 主动下架(发布者本人或超管)
     */
    Boolean offline(Long id);

    /**
     * 审核队列(仅超管)
     */
    PageResult<AgentMarketListingVo> reviewPage(AgentMarketListingBo bo, PageQuery pageQuery);

    /**
     * 审核通过(仅超管)
     */
    Boolean approve(Long id);

    /**
     * 审核驳回(仅超管)
     */
    Boolean reject(Long id, String reason);

    /**
     * 强制下架(仅超管)
     */
    Boolean forceOffline(Long id, String reason);

    /**
     * 安装依赖检查
     */
    AgentInstallCheckVo installCheck(AgentMarketInstallBo bo);

    /**
     * 确认安装(生成 Agent 副本,返回副本ID)
     */
    Long installConfirm(AgentMarketInstallBo bo);

    /**
     * 我的安装记录(含最新版本号与评分状态)
     */
    List<AgentMarketInstallVo> myInstalls(Long workspaceId);

    /**
     * 升级到最新版本(保留资源绑定,留存旧快照支持回退)
     */
    Boolean upgrade(Long installId);

    /**
     * 评分(仅安装人,每个安装记录限评一次,可改评)
     */
    Boolean rate(AgentMarketReviewBo bo);
}
