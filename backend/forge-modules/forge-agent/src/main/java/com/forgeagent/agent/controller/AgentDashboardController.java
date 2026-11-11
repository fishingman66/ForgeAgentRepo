package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.vo.DashboardOverviewVo;
import com.forgeagent.agent.service.IAgentDashboardService;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营看板 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/dashboard")
public class AgentDashboardController extends BaseController {

    private final IAgentDashboardService dashboardService;

    /**
     * 运营看板概览(平台管理员 / 空间管理员,按角色裁剪 section)
     */
    @SaCheckPermission(value = {"agent:dashboard:platform", "agent:dashboard:workspace"}, mode = SaMode.OR)
    @GetMapping("/overview")
    public R<DashboardOverviewVo> overview() {
        return R.ok(dashboardService.overview());
    }
}
