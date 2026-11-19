package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.forgeagent.agent.domain.bo.AgentMcpClientBo;
import com.forgeagent.agent.domain.vo.AgentMcpClientTokenVo;
import com.forgeagent.agent.domain.vo.AgentMcpClientVo;
import com.forgeagent.agent.service.IAgentMcpClientService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 对外 MCP 客户端 Controller(08方案 / A5 增强)
 *
 * <p>明文令牌只在 {@code POST /agent/mcpclient} 与 {@code POST /{id}/rotate} 返回一次,
 * 其余接口只回前缀。</p>
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/mcpclient")
public class AgentMcpClientController extends BaseController {

    private final IAgentMcpClientService clientService;

    @SaCheckPermission("agent:mcpclient:list")
    @GetMapping("/list")
    public R<PageResult<AgentMcpClientVo>> list(@Validated PageQuery pageQuery, AgentMcpClientBo bo) {
        return R.ok(clientService.queryPageList(bo, pageQuery));
    }

    @SaCheckPermission("agent:mcpclient:query")
    @GetMapping("/{id}")
    public R<AgentMcpClientVo> getInfo(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(clientService.queryById(id));
    }

    /**
     * 新建客户端:响应里的 token 只出现这一次
     */
    @SaCheckPermission("agent:mcpclient:add")
    @Log(title = "MCP客户端", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<AgentMcpClientTokenVo> add(@Validated(AddGroup.class) @RequestBody AgentMcpClientBo bo) {
        return R.ok(clientService.createClient(bo));
    }

    @SaCheckPermission("agent:mcpclient:edit")
    @Log(title = "MCP客户端", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentMcpClientBo bo) {
        return toAjax(clientService.updateByBo(bo));
    }

    @SaCheckPermission("agent:mcpclient:edit")
    @Log(title = "MCP客户端", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotNull String enabled) {
        return toAjax(clientService.changeStatus(id, enabled));
    }

    /**
     * 轮转令牌:旧令牌立即失效(调用方需同步更新自身配置)
     */
    @SaCheckPermission("agent:mcpclient:rotate")
    @Log(title = "MCP客户端", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/rotate")
    public R<AgentMcpClientTokenVo> rotate(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(clientService.rotateToken(id));
    }

    @SaCheckPermission("agent:mcpclient:remove")
    @Log(title = "MCP客户端", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空") @PathVariable Long[] ids) {
        return toAjax(clientService.deleteByIds(java.util.List.of(ids)));
    }
}
