package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentMarketInstallBo;
import com.forgeagent.agent.domain.bo.AgentMarketListingBo;
import com.forgeagent.agent.domain.bo.AgentMarketReviewBo;
import com.forgeagent.agent.domain.vo.AgentInstallCheckVo;
import com.forgeagent.agent.domain.vo.AgentMarketInstallVo;
import com.forgeagent.agent.domain.vo.AgentMarketListingVo;
import com.forgeagent.agent.service.IAgentMarketService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能体市场(浏览/安装/升级/评分) Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/market")
public class AgentMarketController extends BaseController {

    private final IAgentMarketService marketService;

    /**
     * 市场浏览(仅已上架)
     */
    @SaCheckPermission("agent:market:list")
    @GetMapping("/list")
    public R<PageResult<AgentMarketListingVo>> list(@Validated PageQuery pageQuery, AgentMarketListingBo bo) {
        return R.ok(marketService.browsePage(bo, pageQuery));
    }

    /**
     * 条目详情
     */
    @SaCheckPermission("agent:market:query")
    @GetMapping("/{id}")
    public R<AgentMarketListingVo> getInfo(@NotNull(message = "主键不能为空")
                                           @PathVariable Long id) {
        return R.ok(marketService.detail(id));
    }

    /**
     * 安装依赖检查
     */
    @SaCheckPermission("agent:market:install")
    @PostMapping("/install/check")
    public R<AgentInstallCheckVo> installCheck(@Validated(AddGroup.class) @RequestBody AgentMarketInstallBo bo) {
        return R.ok(marketService.installCheck(bo));
    }

    /**
     * 确认安装(生成 Agent 副本)
     */
    @SaCheckPermission("agent:market:install")
    @Log(title = "智能体市场", businessType = BusinessType.INSERT)
    @PostMapping("/install/confirm")
    public R<Long> installConfirm(@Validated(AddGroup.class) @RequestBody AgentMarketInstallBo bo) {
        return R.ok("安装成功", marketService.installConfirm(bo));
    }

    /**
     * 我的安装记录(含可升级标记)
     */
    @SaCheckPermission("agent:market:query")
    @GetMapping("/install/list")
    public R<List<AgentMarketInstallVo>> myInstalls(@RequestParam(required = false) Long workspaceId) {
        return R.ok(marketService.myInstalls(workspaceId));
    }

    /**
     * 升级到最新版本
     */
    @SaCheckPermission("agent:market:install")
    @Log(title = "智能体市场", businessType = BusinessType.UPDATE)
    @PutMapping("/upgrade/{installId}")
    public R<Void> upgrade(@NotNull(message = "安装记录ID不能为空")
                           @PathVariable Long installId) {
        return toAjax(marketService.upgrade(installId));
    }

    /**
     * 评分(仅安装人,可改评)
     */
    @SaCheckPermission("agent:market:rate")
    @Log(title = "智能体市场", businessType = BusinessType.INSERT)
    @PostMapping("/rate")
    public R<Void> rate(@Validated(AddGroup.class) @RequestBody AgentMarketReviewBo bo) {
        return toAjax(marketService.rate(bo));
    }
}
