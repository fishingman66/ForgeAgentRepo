package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentSessionBo;
import com.forgeagent.agent.domain.vo.AgentMessageVo;
import com.forgeagent.agent.domain.vo.AgentSessionUsageBarVo;
import com.forgeagent.agent.domain.vo.AgentSessionVo;
import com.forgeagent.agent.service.IAgentSessionService;
import com.forgeagent.agent.service.IAgentTokenUsageService;
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
 * Agent 会话 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/session")
public class AgentSessionController extends BaseController {

    private final IAgentSessionService sessionService;
    private final IAgentTokenUsageService tokenUsageService;

    /**
     * 我的会话分页列表
     */
    @SaCheckPermission("agent:session:list")
    @GetMapping("/list")
    public R<PageResult<AgentSessionVo>> list(@Validated PageQuery pageQuery, AgentSessionBo bo) {
        return R.ok(sessionService.queryPageList(bo, pageQuery));
    }

    /**
     * 新建会话
     */
    @SaCheckPermission("agent:session:add")
    @Log(title = "会话管理", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<AgentSessionVo> add(@Validated(AddGroup.class) @RequestBody AgentSessionBo bo) {
        return R.ok(sessionService.insertByBo(bo));
    }

    /**
     * 重命名会话
     */
    @SaCheckPermission("agent:session:edit")
    @Log(title = "会话管理", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentSessionBo bo) {
        return toAjax(sessionService.updateByBo(bo));
    }

    /**
     * 置顶/取消置顶会话
     */
    @SaCheckPermission("agent:session:edit")
    @Log(title = "会话管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/pinned")
    public R<Void> pinned(@NotNull(message = "主键不能为空") @PathVariable Long id,
                          @RequestParam(defaultValue = "1") String pinned) {
        return toAjax(sessionService.updatePinned(id, pinned));
    }

    /**
     * 删除会话(连同消息)
     */
    @SaCheckPermission("agent:session:remove")
    @Log(title = "会话管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(sessionService.deleteByIds(List.of(ids)));
    }

    /**
     * 会话消息列表(按时间正序,游标分页)
     *
     * @param beforeId 只返回 id 小于该值的消息(为空取最新一页)
     * @param limit    本页条数(默认 50,上限 200)
     */
    @SaCheckPermission("agent:session:query")
    @GetMapping("/{id}/messages")
    public R<List<AgentMessageVo>> messages(@NotNull(message = "主键不能为空")
                                            @PathVariable Long id,
                                            @RequestParam(required = false) Long beforeId,
                                            @RequestParam(required = false) Integer limit) {
        return R.ok(sessionService.listMessages(id, beforeId, limit));
    }

    /**
     * 会话 Token 用量实时反馈(UX-03):累计/本轮/上下文剩余/成本估算
     */
    @SaCheckPermission("agent:session:query")
    @GetMapping("/{id}/usage")
    public R<AgentSessionUsageBarVo> usage(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(tokenUsageService.sessionUsageBar(id));
    }
}
