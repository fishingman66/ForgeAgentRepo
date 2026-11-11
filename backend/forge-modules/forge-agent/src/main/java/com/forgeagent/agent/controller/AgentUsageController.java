package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentTokenUsageBo;
import com.forgeagent.agent.domain.vo.AgentSessionUsageVo;
import com.forgeagent.agent.domain.vo.AgentTokenUsageVo;
import com.forgeagent.agent.domain.vo.AgentUsageReportVo;
import com.forgeagent.agent.service.IAgentTokenUsageService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 用量统计 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/usage")
public class AgentUsageController extends BaseController {

    private final IAgentTokenUsageService usageService;

    /**
     * 用量统计报表(指标卡/趋势/排行)
     */
    @SaCheckPermission("agent:usage:list")
    @GetMapping("/report")
    public R<AgentUsageReportVo> report(AgentTokenUsageBo bo) {
        return R.ok(usageService.report(bo));
    }

    /**
     * 用量明细分页
     */
    @SaCheckPermission("agent:usage:list")
    @GetMapping("/list")
    public R<PageResult<AgentTokenUsageVo>> list(@Validated PageQuery pageQuery, AgentTokenUsageBo bo) {
        return R.ok(usageService.queryPageList(bo, pageQuery));
    }

    /**
     * 会话内消耗汇总
     */
    @SaCheckPermission("agent:usage:list")
    @GetMapping("/session/{sessionId}")
    public R<AgentSessionUsageVo> sessionUsage(@NotNull(message = "会话ID不能为空")
                                               @PathVariable Long sessionId) {
        return R.ok(usageService.sessionUsage(sessionId));
    }

    /**
     * 导出用量明细 Excel
     */
    @SaCheckPermission("agent:usage:export")
    @Log(title = "Token用量导出", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public void export(AgentTokenUsageBo bo, HttpServletResponse response) {
        usageService.export(bo, response);
    }
}
