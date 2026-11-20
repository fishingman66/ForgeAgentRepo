package com.forgeagent.customerservice.controller;

import com.forgeagent.common.core.domain.R;
import com.forgeagent.customerservice.domain.bo.CsInvestigationConfirmBo;
import com.forgeagent.customerservice.domain.vo.CsInvestigationVo;
import com.forgeagent.customerservice.service.CsInvestigationService;
import com.forgeagent.customerservice.service.CsWorkspaceResolver;
import com.forgeagent.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B4 AI 工单根因调查(RCA)端点。
 *
 * <p>全部按坐席当前空间下推:调查记录、工单、数据源三方空间必须一致,否则按"不存在"处理。</p>
 */
@SaCheckLogin
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/customerservice")
public class CsInvestigationController {

    private final CsInvestigationService investigationService;
    private final CsWorkspaceResolver workspaceResolver;

    /** 对指定工单发起 AI 根因调查(异步执行,返回调查 ID) */
    @PostMapping("/tickets/{ticketId}/investigate")
    public R<Long> investigate(@PathVariable Long ticketId,
                               @RequestParam Long datasourceId) {
        return R.ok(investigationService.start(ticketId, datasourceId,
            workspaceResolver.currentWorkspaceId(), LoginHelper.getUserId()));
    }

    /** 某工单的历史调查列表(不含证据链) */
    @GetMapping("/tickets/{ticketId}/investigations")
    public R<List<CsInvestigationVo>> listByTicket(@PathVariable Long ticketId) {
        return R.ok(investigationService.listByTicket(ticketId, workspaceResolver.currentWorkspaceId()));
    }

    /** 调查详情(含报告与证据链) */
    @GetMapping("/investigations/{id}")
    public R<CsInvestigationVo> detail(@PathVariable Long id) {
        return R.ok(investigationService.detail(id, workspaceResolver.currentWorkspaceId()));
    }

    /** 坐席确认/驳回调查结论 */
    @PostMapping("/investigations/{id}/confirm")
    public R<CsInvestigationVo> confirm(@PathVariable Long id,
                                        @RequestBody(required = false) CsInvestigationConfirmBo bo) {
        CsInvestigationConfirmBo body = bo == null ? new CsInvestigationConfirmBo() : bo;
        return R.ok(investigationService.confirm(id, workspaceResolver.currentWorkspaceId(),
            LoginHelper.getUserId(), body));
    }
}
