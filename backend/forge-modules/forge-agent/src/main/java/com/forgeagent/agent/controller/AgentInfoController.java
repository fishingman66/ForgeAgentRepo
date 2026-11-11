package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentInfoBo;
import com.forgeagent.agentcore.domain.vo.AgentInfoVo;
import com.forgeagent.agent.service.IAgentInfoService;
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
 * Agent 定义 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/agent")
public class AgentInfoController extends BaseController {

    private final IAgentInfoService agentService;

    /**
     * 分页查询 Agent(不传 workspaceId 时:超管查全部,普通用户仅可见已加入空间的)
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/list")
    public R<PageResult<AgentInfoVo>> list(@Validated PageQuery pageQuery, AgentInfoBo bo) {
        return R.ok(agentService.queryPageList(bo, pageQuery));
    }

    /**
     * 已发布 Agent 列表(聊天页选择器使用)
     */
    @GetMapping("/published")
    public R<List<AgentInfoVo>> published() {
        return R.ok(agentService.listPublished());
    }

    /**
     * Agent 详情
     */
    @SaCheckPermission("agent:agent:query")
    @GetMapping("/{id}")
    public R<AgentInfoVo> getInfo(@NotNull(message = "主键不能为空")
                                  @PathVariable Long id) {
        return R.ok(agentService.queryById(id));
    }

    /**
     * 新增 Agent(返回新ID)
     */
    @SaCheckPermission("agent:agent:add")
    @Log(title = "Agent管理", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Long> add(@Validated(AddGroup.class) @RequestBody AgentInfoBo bo) {
        return R.ok(agentService.insertByBo(bo));
    }

    /**
     * 修改 Agent
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "Agent管理", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentInfoBo bo) {
        return toAjax(agentService.updateByBo(bo));
    }

    /**
     * 发布/停用 Agent(status: 1发布 2停用)
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "Agent管理", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(agentService.changeStatus(id, status));
    }

    /**
     * 删除 Agent
     */
    @SaCheckPermission("agent:agent:remove")
    @Log(title = "Agent管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(agentService.deleteByIds(List.of(ids)));
    }

    /**
     * 克隆 Agent:复制全部配置到同空间下的新 Agent,状态置为草稿
     */
    @SaCheckPermission("agent:agent:add")
    @Log(title = "Agent管理", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/clone")
    public R<Long> clone(@NotNull(message = "主键不能为空")
                         @PathVariable Long id) {
        return R.ok(agentService.cloneById(id));
    }
}
