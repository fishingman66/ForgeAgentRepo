package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentProviderBo;
import com.forgeagent.agent.domain.vo.AgentProviderVo;
import com.forgeagent.agent.service.IAgentProviderService;
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
 * 模型供应商配置 Controller(平台级)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/provider")
public class AgentProviderController extends BaseController {

    private final IAgentProviderService providerService;

    /**
     * 分页查询供应商
     */
    @SaCheckPermission("agent:provider:list")
    @GetMapping("/list")
    public R<PageResult<AgentProviderVo>> list(@Validated PageQuery pageQuery, AgentProviderBo bo) {
        return R.ok(providerService.queryPageList(bo, pageQuery));
    }

    /**
     * 全部供应商(下拉用)
     */
    @SaCheckPermission("agent:provider:query")
    @GetMapping("/all")
    public R<List<AgentProviderVo>> all() {
        return R.ok(providerService.listAll());
    }

    /**
     * 供应商详情
     */
    @SaCheckPermission("agent:provider:query")
    @GetMapping("/{id}")
    public R<AgentProviderVo> getInfo(@NotNull(message = "主键不能为空")
                                      @PathVariable Long id) {
        return R.ok(providerService.queryById(id));
    }

    /**
     * 新增供应商(仅平台超管)
     */
    @SaCheckPermission("agent:provider:add")
    @Log(title = "供应商配置", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentProviderBo bo) {
        return toAjax(providerService.insertByBo(bo));
    }

    /**
     * 修改供应商(仅平台超管)
     */
    @SaCheckPermission("agent:provider:edit")
    @Log(title = "供应商配置", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentProviderBo bo) {
        return toAjax(providerService.updateByBo(bo));
    }

    /**
     * 启用/停用供应商(仅平台超管)
     */
    @SaCheckPermission("agent:provider:edit")
    @Log(title = "供应商配置", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(providerService.changeStatus(id, status));
    }

    /**
     * 删除供应商(仅平台超管)
     */
    @SaCheckPermission("agent:provider:remove")
    @Log(title = "供应商配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(providerService.deleteByIds(List.of(ids)));
    }
}
