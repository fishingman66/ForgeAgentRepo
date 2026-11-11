package com.forgeagent.agent.service;

import jakarta.servlet.http.HttpServletResponse;
import com.forgeagent.agent.domain.bo.AgentFeedbackBo;
import com.forgeagent.agent.domain.vo.AgentFeedbackVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

/**
 * 消息反馈管理 Service(UX-02)
 *
 * @author ForgeAgent
 */
public interface IAgentFeedbackService {

    /**
     * 反馈分页查询(超管全量;空间管理员仅本空间)
     */
    PageResult<AgentFeedbackVo> queryPageList(AgentFeedbackBo bo, PageQuery pageQuery);

    /**
     * 按筛选条件导出反馈 Excel
     */
    void export(AgentFeedbackBo bo, HttpServletResponse response);
}
