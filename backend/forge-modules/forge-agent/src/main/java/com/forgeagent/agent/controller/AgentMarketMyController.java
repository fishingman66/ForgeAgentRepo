package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentMarketListingBo;
import com.forgeagent.agent.domain.bo.AgentMarketPublishBo;
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

/**
 * 智能体市场·我的发布 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/market/my")
public class AgentMarketMyController extends BaseController {

    private final IAgentMarketService marketService;

    /**
     * 我的发布分页
     */
    @SaCheckPermission("agent:market:my")
    @GetMapping("/list")
    public R<PageResult<AgentMarketListingVo>> list(@Validated PageQuery pageQuery, AgentMarketListingBo bo) {
        return R.ok(marketService.myPage(bo, pageQuery));
    }

    /**
     * 提交发布(仅本人已发布的 Agent)
     */
    @SaCheckPermission("agent:market:publish")
    @Log(title = "智能体市场", businessType = BusinessType.INSERT)
    @PostMapping("/publish")
    public R<Long> publish(@Validated(AddGroup.class) @RequestBody AgentMarketPublishBo bo) {
        return R.ok("已提交审核", marketService.publish(bo));
    }

    /**
     * 主动下架
     */
    @SaCheckPermission("agent:market:offline")
    @Log(title = "智能体市场", businessType = BusinessType.UPDATE)
    @PutMapping("/offline/{id}")
    public R<Void> offline(@NotNull(message = "主键不能为空")
                           @PathVariable Long id) {
        return toAjax(marketService.offline(id));
    }
}
