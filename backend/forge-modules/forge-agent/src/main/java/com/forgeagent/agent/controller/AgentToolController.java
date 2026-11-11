package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentToolBo;
import com.forgeagent.agent.domain.vo.AgentToolVo;
import com.forgeagent.agent.service.IAgentToolService;
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
 * 工具注册 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/tool")
public class AgentToolController extends BaseController {

    private final IAgentToolService toolService;

    /**
     * 分页查询工具
     */
    @SaCheckPermission("agent:tool:list")
    @GetMapping("/list")
    public R<PageResult<AgentToolVo>> list(@Validated PageQuery pageQuery, AgentToolBo bo) {
        return R.ok(toolService.queryPageList(bo, pageQuery));
    }

    /**
     * 工具详情
     */
    @SaCheckPermission("agent:tool:query")
    @GetMapping("/{id}")
    public R<AgentToolVo> getInfo(@NotNull(message = "主键不能为空")
                                  @PathVariable Long id) {
        return R.ok(toolService.queryById(id));
    }

    /**
     * 新增工具
     */
    @SaCheckPermission("agent:tool:add")
    @Log(title = "工具注册", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentToolBo bo) {
        return toAjax(toolService.insertByBo(bo));
    }

    /**
     * 修改工具
     */
    @SaCheckPermission("agent:tool:edit")
    @Log(title = "工具注册", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentToolBo bo) {
        return toAjax(toolService.updateByBo(bo));
    }

    /**
     * 启用/停用工具
     */
    @SaCheckPermission("agent:tool:edit")
    @Log(title = "工具注册", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(toolService.changeStatus(id, status));
    }

    /**
     * 删除工具
     */
    @SaCheckPermission("agent:tool:remove")
    @Log(title = "工具注册", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(toolService.deleteByIds(List.of(ids)));
    }
}
