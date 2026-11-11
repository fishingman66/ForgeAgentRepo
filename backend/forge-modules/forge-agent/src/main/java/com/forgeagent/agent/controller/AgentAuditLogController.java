package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentAuditLogBo;
import com.forgeagent.agentcore.domain.vo.AgentAuditLogVo;
import com.forgeagent.agent.service.IAgentAuditLogService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 平台审计日志 Controller(只读)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/audit")
public class AgentAuditLogController extends BaseController {

    private final IAgentAuditLogService auditLogService;

    /**
     * 分页查询审计日志
     */
    @SaCheckPermission("agent:audit:list")
    @GetMapping("/list")
    public R<PageResult<AgentAuditLogVo>> list(@Validated PageQuery pageQuery, AgentAuditLogBo bo) {
        return R.ok(auditLogService.queryPageList(bo, pageQuery));
    }

    /**
     * 审计日志详情
     */
    @SaCheckPermission("agent:audit:query")
    @GetMapping("/{id}")
    public R<AgentAuditLogVo> getInfo(@NotNull(message = "主键不能为空")
                                      @PathVariable Long id) {
        return R.ok(auditLogService.queryById(id));
    }
}
