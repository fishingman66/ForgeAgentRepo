package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentWorkspaceBo;
import com.forgeagent.agent.domain.vo.AgentWorkspaceVo;
import com.forgeagent.agent.service.IAgentWorkspaceService;
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
 * 工作空间 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/workspace")
public class AgentWorkspaceController extends BaseController {

    private final IAgentWorkspaceService workspaceService;

    /**
     * 我加入的空间列表(含我的角色与成员数)
     */
    @GetMapping("/my")
    public R<List<AgentWorkspaceVo>> myWorkspaces(@RequestParam(required = false) String keyword) {
        return R.ok(workspaceService.myWorkspaces(keyword));
    }

    /**
     * 分页查询全部空间(平台管理视角)
     */
    @SaCheckPermission("agent:workspace:list")
    @GetMapping("/list")
    public R<PageResult<AgentWorkspaceVo>> list(@Validated PageQuery pageQuery, AgentWorkspaceBo bo) {
        return R.ok(workspaceService.queryPageList(bo, pageQuery));
    }

    /**
     * 空间详情
     */
    @SaCheckPermission("agent:workspace:query")
    @GetMapping("/{id}")
    public R<AgentWorkspaceVo> getInfo(@NotNull(message = "主键不能为空")
                                       @PathVariable Long id) {
        return R.ok(workspaceService.queryById(id));
    }

    /**
     * 新增空间
     */
    @SaCheckPermission("agent:workspace:add")
    @Log(title = "工作空间", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentWorkspaceBo bo) {
        return toAjax(workspaceService.insertByBo(bo));
    }

    /**
     * 编辑空间
     */
    @SaCheckPermission("agent:workspace:edit")
    @Log(title = "工作空间", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentWorkspaceBo bo) {
        return toAjax(workspaceService.updateByBo(bo));
    }

    /**
     * 启用/停用空间
     */
    @SaCheckPermission("agent:workspace:edit")
    @Log(title = "工作空间", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(workspaceService.changeStatus(id, status));
    }

    /**
     * 删除空间
     */
    @SaCheckPermission("agent:workspace:remove")
    @Log(title = "工作空间", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(workspaceService.deleteByIds(List.of(ids)));
    }
}
