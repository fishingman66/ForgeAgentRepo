package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentTriggerBo;
import com.forgeagent.agent.domain.vo.AgentTriggerLogVo;
import com.forgeagent.agent.domain.vo.AgentTriggerVo;
import com.forgeagent.agent.domain.vo.TriggerRunVo;
import com.forgeagent.agent.service.IAgentTriggerService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.redis.annotation.RateLimiter;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 触发器 Controller
 *
 * <p>提供 Cron 定时 / Webhook 事件触发器的配置管理、执行日志与手动执行能力。</p>
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/trigger")
public class AgentTriggerController extends BaseController {

    private final IAgentTriggerService triggerService;

    /**
     * 分页查询触发器
     */
    @SaCheckPermission("agent:trigger:list")
    @GetMapping("/list")
    public R<PageResult<AgentTriggerVo>> list(@Validated PageQuery pageQuery, AgentTriggerBo bo) {
        return R.ok(triggerService.queryPageList(bo, pageQuery));
    }

    /**
     * 触发器详情
     */
    @SaCheckPermission("agent:trigger:query")
    @GetMapping("/{id}")
    public R<AgentTriggerVo> getInfo(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(triggerService.queryById(id));
    }

    /**
     * 新增触发器
     */
    @SaCheckPermission("agent:trigger:add")
    @Log(title = "触发器管理", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentTriggerBo bo) {
        return toAjax(triggerService.insertByBo(bo));
    }

    /**
     * 修改触发器
     */
    @SaCheckPermission("agent:trigger:edit")
    @Log(title = "触发器管理", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentTriggerBo bo) {
        return toAjax(triggerService.updateByBo(bo));
    }

    /**
     * 启用/停用触发器
     */
    @SaCheckPermission("agent:trigger:edit")
    @Log(title = "触发器管理", businessType = BusinessType.UPDATE)
    @PutMapping("/enabled")
    public R<Void> changeEnabled(@RequestParam @NotNull Long id,
                                 @RequestParam @NotNull String enabled) {
        return toAjax(triggerService.changeEnabled(id, enabled));
    }

    /**
     * 删除触发器
     */
    @SaCheckPermission("agent:trigger:remove")
    @Log(title = "触发器管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空") @PathVariable Long[] ids) {
        return toAjax(triggerService.deleteByIds(List.of(ids)));
    }

    /**
     * 手动立即执行一次
     */
    @SaCheckPermission("agent:trigger:run")
    @Log(title = "触发器管理", businessType = BusinessType.OTHER)
    @PostMapping("/run/{id}")
    public R<TriggerRunVo> run(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(triggerService.runNow(id));
    }

    /**
     * 执行记录分页查询
     */
    @SaCheckPermission("agent:trigger:list")
    @GetMapping("/log/list")
    public R<PageResult<AgentTriggerLogVo>> logList(@RequestParam @NotNull Long triggerId,
                                                    @Validated PageQuery pageQuery) {
        return R.ok(triggerService.queryLogPageList(triggerId, pageQuery));
    }

    /**
     * Webhook 事件触发入口(路径已在安全配置中排除登录校验,以 secret 鉴权)
     */
    @RateLimiter(key = "#secret", time = 60, count = 60,
        limitType = com.forgeagent.common.redis.enums.LimitType.IP,
        message = "触发太频繁，请稍后再试")
    @Log(title = "触发器管理", businessType = BusinessType.OTHER)
    @PostMapping("/webhook/{secret}")
    public R<TriggerRunVo> webhook(@PathVariable String secret,
                                   @RequestBody(required = false) String body,
                                   @org.springframework.web.bind.annotation.RequestHeader(
                                       value = "Idempotency-Key", required = false) String idempotencyKey) {
        // 体检 R2:免登录入口请求体上限,防超长 body 打爆 TEXT 列与 LLM 调用
        if (body != null && body.length() > MAX_WEBHOOK_BODY_CHARS) {
            return R.fail("请求体过大,上限 " + (MAX_WEBHOOK_BODY_CHARS / 1024) + "KB");
        }
        return R.ok(triggerService.runWebhook(secret, body, idempotencyKey));
    }

    /** 体检 R2:webhook 请求体上限(字符) */
    private static final int MAX_WEBHOOK_BODY_CHARS = 256 * 1024;
}
