package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.vo.AgentApprovalVo;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 工具审批中心 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/approval")
public class AgentApprovalController extends BaseController {

    private final IAgentApprovalService approvalService;

    /**
     * 分页查询审批单
     */
    @SaCheckPermission("agent:approval:list")
    @GetMapping("/list")
    public R<PageResult<AgentApprovalVo>> list(@Validated PageQuery pageQuery,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String toolSource) {
        return R.ok(approvalService.queryPageList(status, keyword, toolSource, pageQuery));
    }

    /**
     * 审批通过
     */
    @SaCheckPermission("agent:approval:handle")
    @Log(title = "工具审批", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/approve")
    public R<Void> approve(@NotNull(message = "主键不能为空") @PathVariable Long id,
                           @RequestParam(required = false) String note) {
        return toAjax(approvalService.handle(id, true, note));
    }

    /**
     * 审批拒绝
     */
    @SaCheckPermission("agent:approval:handle")
    @Log(title = "工具审批", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reject")
    public R<Void> reject(@NotNull(message = "主键不能为空") @PathVariable Long id,
                          @RequestParam(required = false) String note) {
        return toAjax(approvalService.handle(id, false, note));
    }
}
