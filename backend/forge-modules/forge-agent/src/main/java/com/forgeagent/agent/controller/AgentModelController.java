package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentModelBo;
import com.forgeagent.agentcore.domain.vo.AgentModelVo;
import com.forgeagent.agent.service.IAgentModelService;
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
 * 模型配置 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/model")
public class AgentModelController extends BaseController {

    private final IAgentModelService modelService;

    /**
     * 分页查询模型配置
     */
    @SaCheckPermission("agent:model:list")
    @GetMapping("/list")
    public R<PageResult<AgentModelVo>> list(@Validated PageQuery pageQuery, AgentModelBo bo) {
        return R.ok(modelService.queryPageList(bo, pageQuery));
    }

    /**
     * 模型配置详情
     */
    @SaCheckPermission("agent:model:query")
    @GetMapping("/{id}")
    public R<AgentModelVo> getInfo(@NotNull(message = "主键不能为空")
                                   @PathVariable Long id) {
        return R.ok(modelService.queryById(id));
    }

    /**
     * 新增模型配置
     */
    @SaCheckPermission("agent:model:add")
    @Log(title = "模型配置", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentModelBo bo) {
        return toAjax(modelService.insertByBo(bo));
    }

    /**
     * 修改模型配置
     */
    @SaCheckPermission("agent:model:edit")
    @Log(title = "模型配置", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentModelBo bo) {
        return toAjax(modelService.updateByBo(bo));
    }

    /**
     * 启用/停用模型
     */
    @SaCheckPermission("agent:model:edit")
    @Log(title = "模型配置", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(modelService.changeStatus(id, status));
    }

    /**
     * 删除模型配置
     */
    @SaCheckPermission("agent:model:remove")
    @Log(title = "模型配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(modelService.deleteByIds(List.of(ids)));
    }
}
