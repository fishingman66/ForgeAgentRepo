package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.forgeagent.agent.service.IAgentInfoService;
import com.forgeagent.agent.service.governance.AgentVersionService;
import com.forgeagent.agent.service.governance.EvaluationService;
import com.forgeagent.agent.service.governance.PromptAuthorService;
import com.forgeagent.agent.service.governance.PromptTemplateService;
import com.forgeagent.agent.service.governance.ReleaseGateService;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.core.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 治理控制台(P1):版本快照 / PromptOps / 效果评测 / 发布门禁。
 *
 * <p>体检 M1 修订:此前仅靠类级权限注解,空间校验缺位——任意 agentId/workspaceId
 * 直传即可跨空间读快照、开豁免、跑评测。现收敛为:agentId 类端点统一经
 * {@link #requireAgentWithMemberCheck}(读=空间成员,评测执行/门禁豁免/回滚=空间开发者);
 * workspaceId 直传参数必须属于当前用户所在空间。</p>
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/governance")
@SaCheckPermission("agent:agent:list")
public class AgentGovernanceController extends BaseController {

    private final AgentVersionService versionService;
    private final PromptTemplateService promptService;
    private final PromptAuthorService promptAuthorService;
    private final EvaluationService evaluationService;
    private final ReleaseGateService gateService;
    private final IAgentInfoService agentInfoService;
    private final AgentInfoMapper agentMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final AgentWorkspaceMemberMapper memberMapper;

    // ---------------------------------------------------------------- B1

    @GetMapping("/versions")
    public R<List<com.forgeagent.agent.domain.vo.AgentVersionVo>> versions(@RequestParam Long agentId) {
        return R.ok(versionService.listVersions(requireAgentWithMemberCheck(agentId).getId()));
    }

    @GetMapping("/version")
    public R<com.forgeagent.agent.domain.vo.AgentVersionVo> version(@RequestParam Long agentId, @RequestParam int versionNo) {
        return R.ok(versionService.versionDetail(requireAgentWithMemberCheck(agentId).getId(), versionNo));
    }

    @GetMapping("/diff")
    public R<com.forgeagent.agent.domain.vo.VersionDiffVo> diff(@RequestParam Long agentId,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(versionService.diff(requireAgentWithMemberCheck(agentId).getId(), from, to));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/rollback")
    public R<Void> rollback(@Validated @RequestBody com.forgeagent.agent.domain.bo.GovernanceRollbackBo bo) {
        requireAgentWithMemberCheck(bo.getAgentId());
        agentInfoService.rollbackVersion(bo.getAgentId(), bo.getVersionNo());
        return R.ok();
    }

    // ---------------------------------------------------------------- 门禁

    @GetMapping("/gate/preview")
    public R<ReleaseGateService.GateResult> gatePreview(@RequestParam Long agentId) {
        return R.ok(gateService.preview(requireAgentWithMemberCheck(agentId)));
    }

    @GetMapping("/gate/executions")
    public R<List<com.forgeagent.agent.domain.vo.ReleaseGateExecutionVo>> gateExecutions(@RequestParam Long agentId) {
        return R.ok(gateService.listExecutions(requireAgentWithMemberCheck(agentId).getId()));
    }

    @GetMapping("/gate/waivers")
    public R<List<com.forgeagent.agent.domain.vo.ReleaseGateWaiverVo>> gateWaivers(@RequestParam Long agentId) {
        return R.ok(gateService.listWaivers(requireAgentWithMemberCheck(agentId).getId()));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/gate/waiver")
    public R<Long> requestWaiver(@Validated @RequestBody com.forgeagent.agent.domain.bo.GateWaiverRequestBo bo) {
        requireAgentWithDeveloperCheck(bo.getAgentId());
        return R.ok(gateService.requestWaiver(bo.getAgentId(), bo.getReason()));
    }

    @SaCheckPermission("agent:agent:edit")
    @PutMapping("/gate/waiver/approve")
    public R<Void> approveWaiver(@RequestParam Long id, @RequestParam boolean approve) {
        gateService.approveWaiver(id, approve);
        return R.ok();
    }

    // ---------------------------------------------------------------- B2

    @GetMapping("/prompts")
    public R<List<com.forgeagent.agent.domain.vo.AgentPromptTemplateVo>> prompts(@RequestParam(required = false) Long workspaceId) {
        return R.ok(promptService.listTemplates(resolveWorkspaceParam(workspaceId)));
    }

    @GetMapping("/prompt")
    public R<com.forgeagent.agent.domain.vo.AgentPromptTemplateVo> prompt(@RequestParam Long id) {
        return R.ok(promptService.getTemplate(id));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/prompt")
    public R<Long> savePrompt(@Validated @RequestBody com.forgeagent.agent.domain.bo.PromptTemplateSaveBo bo) {
        return R.ok(promptService.saveTemplate(bo));
    }

    @GetMapping("/prompt/versions")
    public R<List<com.forgeagent.agent.domain.vo.AgentPromptVersionVo>> promptVersions(@RequestParam Long templateId) {
        return R.ok(promptService.listVersions(templateId));
    }

    @GetMapping("/prompt/version")
    public R<com.forgeagent.agent.domain.vo.AgentPromptVersionVo> promptVersion(@RequestParam Long versionId) {
        return R.ok(promptService.getVersion(versionId));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/prompt/version")
    public R<Long> publishPromptVersion(@Validated @RequestBody com.forgeagent.agent.domain.bo.PromptVersionPublishBo bo) {
        return R.ok(promptService.publishVersion(bo.getTemplateId(), bo.getContent(),
            bo.getVariableSchema(), bo.getRemark(),
            Boolean.TRUE.equals(bo.getAsStable())));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/prompt/stable")
    public R<Void> setStable(@Validated @RequestBody com.forgeagent.agent.domain.bo.PromptStableBo bo) {
        promptService.setStable(bo.getTemplateId(), bo.getVersionId());
        return R.ok();
    }

    /** C4:业务描述 → system prompt 草稿(不落库,人工确认后再采纳) */
    @PostMapping("/prompt/generate")
    public R<com.forgeagent.agent.domain.vo.PromptGenerateVo> generatePrompt(
        @RequestBody com.forgeagent.agent.domain.bo.PromptGenerateBo bo) {
        return R.ok(promptAuthorService.generate(bo));
    }

    /** C4:草稿内容直接渲染预览(变量契约与模板同格式,不落版本链) */
    @PostMapping("/prompt/preview-draft")
    public R<com.forgeagent.agent.domain.vo.PromptPreviewVo> previewDraft(
        @RequestBody com.forgeagent.agent.domain.bo.PromptDraftPreviewBo bo) {
        return R.ok(promptService.previewDraft(bo.getContent(), bo.getVariableSchema(), bo.getVariables()));
    }

    @PostMapping("/prompt/preview")
    public R<com.forgeagent.agent.domain.vo.PromptPreviewVo> promptPreview(@Validated @RequestBody com.forgeagent.agent.domain.bo.PromptPreviewBo bo) {
        return R.ok(promptService.preview(bo.getVersionId(), bo.getVariables()));
    }

    // ---------------------------------------------------------------- B3

    @GetMapping("/eval/datasets")
    public R<List<com.forgeagent.agent.domain.vo.AgentEvalDatasetVo>> datasets(@RequestParam(required = false) Long workspaceId) {
        return R.ok(evaluationService.listDatasets(resolveWorkspaceParam(workspaceId)));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/dataset")
    public R<Long> createDataset(@Validated @RequestBody com.forgeagent.agent.domain.bo.EvalDatasetCreateBo bo) {
        return R.ok(evaluationService.createDataset(bo));
    }

    @GetMapping("/eval/cases")
    public R<List<com.forgeagent.agent.domain.vo.AgentEvalCaseVo>> cases(@RequestParam Long datasetId) {
        return R.ok(evaluationService.listCases(datasetId));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/cases")
    public R<Integer> addCases(@Validated @RequestBody com.forgeagent.agent.domain.bo.EvalCasesAddBo bo) {
        return R.ok(evaluationService.addCases(bo));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/run")
    public R<com.forgeagent.agent.domain.vo.EvalRunOutcomeVo> runEval(@Validated @RequestBody com.forgeagent.agent.domain.bo.EvalRunStartBo bo) {
        requireAgentWithDeveloperCheck(bo.getAgentId());
        return R.ok(evaluationService.run(bo));
    }

    @GetMapping("/eval/run")
    public R<com.forgeagent.agent.domain.vo.EvalRunDetailVo> evalRun(@RequestParam Long runId) {
        return R.ok(evaluationService.runDetail(runId));
    }

    @GetMapping("/eval/runs")
    public R<List<com.forgeagent.agent.domain.vo.AgentEvalRunVo>> evalRuns(@RequestParam Long agentId) {
        return R.ok(evaluationService.listRuns(requireAgentWithMemberCheck(agentId).getId()));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/baseline")
    public R<Long> createBaseline(@RequestParam Long runId) {
        return R.ok(evaluationService.createBaseline(runId));
    }

    private AgentInfo requireAgentWithMemberCheck(Long agentId) {
        AgentInfo agent = requireAgent(agentId);
        permissionHelper.checkWsMember(agent.getWorkspaceId());
        return agent;
    }

    private AgentInfo requireAgentWithDeveloperCheck(Long agentId) {
        AgentInfo agent = requireAgent(agentId);
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        return agent;
    }

    /**
     * 体检 M1:workspaceId 直传参数的服务端解析——
     * 超管按原样;非超管传值须为其所在空间,未传则取其首个成员空间(前端筛选未选时兜底)。
     */
    private Long resolveWorkspaceParam(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return workspaceId;
        }
        List<AgentWorkspaceMember> members = memberMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId()));
        if (members.isEmpty()) {
            throw new ServiceException("请先加入工作空间");
        }
        List<Long> joined = members.stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
        if (workspaceId == null) {
            return joined.getFirst();
        }
        if (!joined.contains(workspaceId)) {
            throw new ServiceException("无权访问该工作空间");
        }
        return workspaceId;
    }

    private AgentInfo requireAgent(Long agentId) {
        AgentInfo agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        return agent;
    }

}
