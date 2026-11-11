package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentWorkspaceMemberBo;
import com.forgeagent.agentcore.domain.vo.AgentWorkspaceMemberVo;
import com.forgeagent.agent.service.IAgentWorkspaceMemberService;
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
 * 工作空间成员 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/workspace/member")
public class AgentWorkspaceMemberController extends BaseController {

    private final IAgentWorkspaceMemberService memberService;

    /**
     * 空间成员分页列表
     */
    @SaCheckPermission("agent:workspace:member")
    @GetMapping("/list")
    public R<PageResult<AgentWorkspaceMemberVo>> list(@RequestParam @NotNull Long workspaceId,
                                                      PageQuery pageQuery) {
        return R.ok(memberService.queryPageList(workspaceId, pageQuery));
    }

    /**
     * 可加入空间的候选用户
     */
    @SaCheckPermission("agent:workspace:member")
    @GetMapping("/candidates")
    public R<List<AgentWorkspaceMemberVo>> candidates(@RequestParam @NotNull Long workspaceId,
                                                      @RequestParam(required = false) String keyword) {
        return R.ok(memberService.candidates(workspaceId, keyword));
    }

    /**
     * 添加成员
     */
    @SaCheckPermission("agent:workspace:member")
    @Log(title = "空间成员", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentWorkspaceMemberBo bo) {
        return toAjax(memberService.addMember(bo));
    }

    /**
     * 变更成员角色
     */
    @SaCheckPermission("agent:workspace:member")
    @Log(title = "空间成员", businessType = BusinessType.UPDATE)
    @PutMapping("/role")
    public R<Void> changeRole(@Validated(EditGroup.class) @RequestBody AgentWorkspaceMemberBo bo) {
        return toAjax(memberService.changeRole(bo));
    }

    /**
     * 移除成员
     */
    @SaCheckPermission("agent:workspace:member")
    @Log(title = "空间成员", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(memberService.removeByIds(List.of(ids)));
    }
}
