package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.WorkflowGraphBo;
import com.forgeagent.agent.domain.bo.WorkflowTrialRunBo;
import com.forgeagent.agent.domain.vo.AgentWorkflowRunVo;
import com.forgeagent.agent.domain.vo.AgentWorkflowVersionVo;
import com.forgeagent.agent.domain.vo.WorkflowRunDetailVo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.domain.vo.WorkflowValidateVo;
import com.forgeagent.agent.service.IAgentWorkflowService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作流编排 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/workflow")
public class AgentWorkflowController extends BaseController {

    private final IAgentWorkflowService agentWorkflowService;

    /**
     * 保存工作流图(自动生成版本快照,返回新版本号)
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流编排", businessType = BusinessType.UPDATE)
    @PostMapping("/save")
    public R<Long> save(@Valid @RequestBody WorkflowGraphBo bo) {
        return R.ok("保存成功", agentWorkflowService.saveWorkflow(bo));
    }

    /**
     * 校验工作流图
     */
    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/validate")
    public R<WorkflowValidateVo> validate(@RequestBody WorkflowGraphBo bo) {
        return R.ok(agentWorkflowService.validateWorkflow(bo.getWorkflowJson()));
    }

    /**
     * 试运行工作流(执行过程落库,支持事后回放)
     */
    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/trial-run")
    public R<WorkflowTrialRunVo> trialRun(@Valid @RequestBody WorkflowTrialRunBo bo) {
        return R.ok(agentWorkflowService.trialRun(bo));
    }

    /**
     * W9:异步试运行:立即返回运行实例 ID,进度经 /run/{runId}/events 的 SSE 逐节点推送
     */
    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/trial-run/async")
    public R<Long> trialRunAsync(@Valid @RequestBody WorkflowTrialRunBo bo) {
        return R.ok("已提交执行", agentWorkflowService.trialRunAsync(bo));
    }

    /**
     * W9:订阅试运行执行事件(先重放 after 之后的事件,再跟随至终态自动收口,支持断线续传)
     */
    @SaCheckPermission("agent:agent:edit")
    @GetMapping("/run/{runId}/events")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter runEvents(
        @NotNull(message = "运行实例ID不能为空") @PathVariable Long runId,
        @RequestParam(defaultValue = "0") Long after) {
        return agentWorkflowService.runEvents(runId, after);
    }

    /**
     * 人工审批决策:更新关联审批单状态并恢复运行实例续跑
     *
     * <p>审批中心处理同样会触发恢复,本接口供工作流页与测试直接决策。</p>
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流编排", businessType = BusinessType.UPDATE)
    @PostMapping("/run/{runId}/respond")
    public R<WorkflowTrialRunVo> respond(@NotNull(message = "运行实例ID不能为空") @PathVariable Long runId,
                                         @RequestParam boolean approved) {
        return R.ok(agentWorkflowService.respondRun(runId, approved));
    }

    /**
     * 版本历史列表(倒序,不含图快照)
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/version/list")
    public R<List<AgentWorkflowVersionVo>> listVersion(@NotNull(message = "AgentID不能为空") @RequestParam Long agentId) {
        return R.ok(agentWorkflowService.listVersion(agentId));
    }

    /**
     * 版本详情(含图快照)
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/version/{versionId}")
    public R<AgentWorkflowVersionVo> getVersion(@NotNull(message = "版本ID不能为空") @PathVariable Long versionId) {
        return R.ok(agentWorkflowService.getVersion(versionId));
    }

    /**
     * 回滚到指定版本(写回草稿并生成新版本)
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "工作流编排", businessType = BusinessType.UPDATE)
    @PostMapping("/version/{versionId}/rollback")
    public R<Long> rollbackVersion(@NotNull(message = "版本ID不能为空") @PathVariable Long versionId) {
        return R.ok("回滚成功", agentWorkflowService.rollbackVersion(versionId));
    }

    /**
     * 运行历史分页列表(倒序)
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/run/list")
    public R<PageResult<AgentWorkflowRunVo>> listRun(@NotNull(message = "AgentID不能为空") @RequestParam Long agentId,
                                                     @Validated PageQuery pageQuery) {
        return R.ok(agentWorkflowService.listRun(agentId, pageQuery));
    }

    /**
     * 运行回放详情(图快照/输入/变量/节点轨迹)
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/run/{runId}")
    public R<WorkflowRunDetailVo> getRunDetail(@NotNull(message = "运行实例ID不能为空") @PathVariable Long runId) {
        return R.ok(agentWorkflowService.getRunDetail(runId));
    }
}
