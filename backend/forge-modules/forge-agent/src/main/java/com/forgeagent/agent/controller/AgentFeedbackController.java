package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentFeedbackBo;
import com.forgeagent.agent.domain.vo.AgentFeedbackVo;
import com.forgeagent.agent.service.IAgentFeedbackService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息反馈管理(UX-02):赞踩记录列表与导出
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/feedback")
public class AgentFeedbackController extends BaseController {

    private final IAgentFeedbackService feedbackService;

    /**
     * 反馈分页列表(按 agent/类型/时间筛选)
     */
    @SaCheckPermission("agent:feedback:list")
    @GetMapping("/page")
    public R<PageResult<AgentFeedbackVo>> page(AgentFeedbackBo bo, PageQuery pageQuery) {
        return R.ok(feedbackService.queryPageList(bo, pageQuery));
    }

    /**
     * 反馈导出
     */
    @SaCheckPermission("agent:feedback:export")
    @Log(title = "消息反馈", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public void export(AgentFeedbackBo bo, HttpServletResponse response) {
        feedbackService.export(bo, response);
    }
}
