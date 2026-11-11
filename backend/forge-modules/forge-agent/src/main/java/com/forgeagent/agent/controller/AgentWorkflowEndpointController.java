package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentWorkflowEndpointBo;
import com.forgeagent.agent.domain.vo.AgentWorkflowEndpointVo;
import com.forgeagent.agent.service.IAgentWorkflowEndpointService;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作流对外 API 端点管理(G2)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/workflow-endpoint")
public class AgentWorkflowEndpointController extends BaseController {

    private final IAgentWorkflowEndpointService endpointService;

    /**
     * 分页查询(按 Agent 过滤时校验其空间开发者权限,否则按空间管理员范围裁剪)
     */
    @SaCheckPermission("agent:agent:edit")
    @GetMapping("/list")
    public R<com.forgeagent.common.core.domain.PageResult<AgentWorkflowEndpointVo>> list(
        @RequestParam(required = false) Long agentId,
        @Validated PageQuery pageQuery) {
        return R.ok(endpointService.queryPageList(agentId, pageQuery));
    }

    /**
     * 创建端点(仅空间管理员)
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流API端点", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Valid @RequestBody AgentWorkflowEndpointBo bo) {
        return R.ok(endpointService.insertByBo(bo));
    }

    /**
     * 更新端点(编码与归属不可改)
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流API端点", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Boolean> edit(@Valid @RequestBody AgentWorkflowEndpointBo bo) {
        return R.ok(endpointService.updateByBo(bo));
    }

    /**
     * 启用/停用
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流API端点", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status/{status}")
    public R<Boolean> changeStatus(@NotNull(message = "端点ID不能为空") @PathVariable Long id,
                                   @PathVariable String status) {
        return R.ok(endpointService.changeStatus(id, status));
    }

    /**
     * 删除端点
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流API端点", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Boolean> remove(@PathVariable List<Long> ids) {
        return R.ok(endpointService.deleteByIds(ids));
    }
}
