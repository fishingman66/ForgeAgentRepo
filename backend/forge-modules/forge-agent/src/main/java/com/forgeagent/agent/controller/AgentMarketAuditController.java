package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentMarketListingBo;
import com.forgeagent.agent.domain.vo.AgentMarketListingVo;
import com.forgeagent.agent.service.IAgentMarketService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 智能体市场·审核队列 Controller(平台管理员)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/market/audit")
public class AgentMarketAuditController extends BaseController {

    private final IAgentMarketService marketService;

    /**
     * 审核队列分页(默认待审核)
     */
    @SaCheckPermission("agent:market:review")
    @GetMapping("/list")
    public R<PageResult<AgentMarketListingVo>> list(@Validated PageQuery pageQuery, AgentMarketListingBo bo) {
        return R.ok(marketService.reviewPage(bo, pageQuery));
    }

    /**
     * 审核通过
     */
    @SaCheckPermission("agent:market:audit")
    @Log(title = "市场审核", businessType = BusinessType.UPDATE)
    @PutMapping("/approve/{id}")
    public R<Void> approve(@NotNull(message = "主键不能为空")
                           @PathVariable Long id) {
        return toAjax(marketService.approve(id));
    }

    /**
     * 审核驳回
     */
    @SaCheckPermission("agent:market:audit")
    @Log(title = "市场审核", businessType = BusinessType.UPDATE)
    @PutMapping("/reject/{id}")
    public R<Void> reject(@NotNull(message = "主键不能为空")
                          @PathVariable Long id,
                          @RequestParam @NotBlank(message = "驳回理由不能为空")
                          @Size(max = 300, message = "理由不能超过 300 字符") String reason) {
        return toAjax(marketService.reject(id, reason));
    }

    /**
     * 强制下架
     */
    @SaCheckPermission("agent:market:forceoff")
    @Log(title = "市场审核", businessType = BusinessType.UPDATE)
    @PutMapping("/forceoff/{id}")
    public R<Void> forceOffline(@NotNull(message = "主键不能为空")
                                @PathVariable Long id,
                                @RequestParam(required = false)
                                @Size(max = 300, message = "理由不能超过 300 字符") String reason) {
        return toAjax(marketService.forceOffline(id, reason));
    }
}
