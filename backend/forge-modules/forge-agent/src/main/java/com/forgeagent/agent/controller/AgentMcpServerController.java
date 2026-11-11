package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentMcpServerBo;
import com.forgeagent.agent.domain.vo.AgentMcpServerVo;
import com.forgeagent.agent.service.IAgentMcpServerService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MCP 服务器注册 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/mcp")
public class AgentMcpServerController extends BaseController {

    private final IAgentMcpServerService mcpServerService;

    /**
     * 分页查询 MCP 服务器
     */
    @SaCheckPermission("agent:mcp:list")
    @GetMapping("/list")
    public R<PageResult<AgentMcpServerVo>> list(@Validated PageQuery pageQuery, AgentMcpServerBo bo) {
        return R.ok(mcpServerService.queryPageList(bo, pageQuery));
    }

    /**
     * MCP 服务器详情
     */
    @SaCheckPermission("agent:mcp:query")
    @GetMapping("/{id}")
    public R<AgentMcpServerVo> getInfo(@NotNull(message = "主键不能为空")
                                       @PathVariable Long id) {
        return R.ok(mcpServerService.queryById(id));
    }

    /**
     * 新增 MCP 服务器
     */
    @SaCheckPermission("agent:mcp:add")
    @Log(title = "MCP服务器管理", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentMcpServerBo bo) {
        return toAjax(mcpServerService.insertByBo(bo));
    }

    /**
     * 修改 MCP 服务器
     */
    @SaCheckPermission("agent:mcp:edit")
    @Log(title = "MCP服务器管理", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentMcpServerBo bo) {
        return toAjax(mcpServerService.updateByBo(bo));
    }

    /**
     * 启用/停用 MCP 服务器
     */
    @SaCheckPermission("agent:mcp:edit")
    @Log(title = "MCP服务器管理", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotNull String status) {
        return toAjax(mcpServerService.changeStatus(id, status));
    }

    /**
     * 删除 MCP 服务器
     */
    @SaCheckPermission("agent:mcp:remove")
    @Log(title = "MCP服务器管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(mcpServerService.deleteByIds(List.of(ids)));
    }

    /**
     * 连接测试:真实建立 MCP 连接并列出工具
     */
    @SaCheckPermission("agent:mcp:test")
    @Log(title = "MCP服务器管理", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/test")
    public R<List<String>> testConnection(@NotNull(message = "主键不能为空")
                                          @PathVariable Long id) {
        return R.ok(mcpServerService.testConnection(id));
    }
}
