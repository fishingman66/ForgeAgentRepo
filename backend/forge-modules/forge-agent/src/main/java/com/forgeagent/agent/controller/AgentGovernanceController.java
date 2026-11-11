package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.forgeagent.agent.service.IAgentInfoService;
import com.forgeagent.agent.service.governance.AgentVersionService;
import com.forgeagent.agent.service.governance.EvaluationService;
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
    private final EvaluationService evaluationService;
    private final ReleaseGateService gateService;
    private final IAgentInfoService agentInfoService;
    private final AgentInfoMapper agentMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final AgentWorkspaceMemberMapper memberMapper;

    // ---------------------------------------------------------------- B1

    @GetMapping("/versions")
    public R<Object> versions(@RequestParam Long agentId) {
        return R.ok(versionService.listVersions(requireAgentWithMemberCheck(agentId).getId()));
    }

    @GetMapping("/version")
    public R<Map<String, Object>> version(@RequestParam Long agentId, @RequestParam int versionNo) {
        return R.ok(versionService.versionDetail(requireAgentWithMemberCheck(agentId).getId(), versionNo));
    }

    @GetMapping("/diff")
    public R<Map<String, Object>> diff(@RequestParam Long agentId,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(versionService.diff(requireAgentWithMemberCheck(agentId).getId(), from, to));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/rollback")
    public R<Void> rollback(@RequestBody Map<String, Object> body) {
        Long agentId = longVal(body.get("agentId"));
        Integer versionNo = intVal(body.get("versionNo"));
        if (agentId == null || versionNo == null) {
            throw new ServiceException("agentId 与 versionNo 不能为空");
        }
        requireAgentWithMemberCheck(agentId);
        agentInfoService.rollbackVersion(agentId, versionNo);
        return R.ok();
    }

    // ---------------------------------------------------------------- 门禁

    @GetMapping("/gate/preview")
    public R<ReleaseGateService.GateResult> gatePreview(@RequestParam Long agentId) {
        return R.ok(gateService.preview(requireAgentWithMemberCheck(agentId)));
    }

    @GetMapping("/gate/executions")
    public R<Object> gateExecutions(@RequestParam Long agentId) {
        return R.ok(gateService.listExecutions(requireAgentWithMemberCheck(agentId).getId()));
    }

    @GetMapping("/gate/waivers")
    public R<Object> gateWaivers(@RequestParam Long agentId) {
        return R.ok(gateService.listWaivers(requireAgentWithMemberCheck(agentId).getId()));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/gate/waiver")
    public R<Long> requestWaiver(@RequestBody Map<String, Object> body) {
        Long agentId = longVal(body.get("agentId"));
        if (agentId == null) {
            throw new ServiceException("agentId 不能为空");
        }
        requireAgentWithDeveloperCheck(agentId);
        String reason = text(body.get("reason"));
        return R.ok(gateService.requestWaiver(agentId, reason));
    }

    @SaCheckPermission("agent:agent:edit")
    @PutMapping("/gate/waiver/approve")
    public R<Void> approveWaiver(@RequestParam Long id, @RequestParam boolean approve) {
        gateService.approveWaiver(id, approve);
        return R.ok();
    }

    // ---------------------------------------------------------------- B2

    @GetMapping("/prompts")
    public R<Object> prompts(@RequestParam(required = false) Long workspaceId) {
        return R.ok(promptService.listTemplates(resolveWorkspaceParam(workspaceId)));
    }

    @GetMapping("/prompt")
    public R<Map<String, Object>> prompt(@RequestParam Long id) {
        return R.ok(promptService.getTemplate(id));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/prompt")
    public R<Long> savePrompt(@RequestBody Map<String, Object> body) {
        return R.ok(promptService.saveTemplate(body));
    }

    @GetMapping("/prompt/versions")
    public R<Object> promptVersions(@RequestParam Long templateId) {
        return R.ok(promptService.listVersions(templateId));
    }

    @GetMapping("/prompt/version")
    public R<Map<String, Object>> promptVersion(@RequestParam Long versionId) {
        return R.ok(promptService.getVersion(versionId));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/prompt/version")
    public R<Long> publishPromptVersion(@RequestBody Map<String, Object> body) {
        Long templateId = longVal(body.get("templateId"));
        if (templateId == null) {
            throw new ServiceException("templateId 不能为空");
        }
        return R.ok(promptService.publishVersion(templateId, text(body.get("content")),
            text(body.get("variableSchema")), text(body.get("remark")),
            Boolean.parseBoolean(String.valueOf(body.getOrDefault("asStable", false)))));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/prompt/stable")
    public R<Void> setStable(@RequestBody Map<String, Object> body) {
        Long templateId = longVal(body.get("templateId"));
        Long versionId = longVal(body.get("versionId"));
        if (templateId == null || versionId == null) {
            throw new ServiceException("templateId 与 versionId 不能为空");
        }
        promptService.setStable(templateId, versionId);
        return R.ok();
    }

    @PostMapping("/prompt/preview")
    public R<Map<String, Object>> promptPreview(@RequestBody Map<String, Object> body) {
        Long versionId = longVal(body.get("versionId"));
        if (versionId == null) {
            throw new ServiceException("versionId 不能为空");
        }
        return R.ok(promptService.preview(versionId, text(body.get("variables"))));
    }

    // ---------------------------------------------------------------- B3

    @GetMapping("/eval/datasets")
    public R<Object> datasets(@RequestParam(required = false) Long workspaceId) {
        return R.ok(evaluationService.listDatasets(resolveWorkspaceParam(workspaceId)));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/dataset")
    public R<Long> createDataset(@RequestBody Map<String, Object> body) {
        return R.ok(evaluationService.createDataset(body));
    }

    @GetMapping("/eval/cases")
    public R<Object> cases(@RequestParam Long datasetId) {
        return R.ok(evaluationService.listCases(datasetId));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/cases")
    public R<Integer> addCases(@RequestBody Map<String, Object> body) {
        Long datasetId = longVal(body.get("datasetId"));
        if (datasetId == null) {
            throw new ServiceException("datasetId 不能为空");
        }
        return R.ok(evaluationService.addCases(datasetId, body.get("cases")));
    }

    @SaCheckPermission("agent:agent:edit")
    @PostMapping("/eval/run")
    public R<Map<String, Object>> runEval(@RequestBody Map<String, Object> body) {
        Long datasetId = longVal(body.get("datasetId"));
        Long agentId = longVal(body.get("agentId"));
        if (datasetId == null || agentId == null) {
            throw new ServiceException("datasetId 与 agentId 不能为空");
        }
        requireAgentWithDeveloperCheck(agentId);
        return R.ok(evaluationService.run(datasetId, agentId,
            Boolean.parseBoolean(String.valueOf(body.getOrDefault("judgeEnabled", false)))));
    }

    @GetMapping("/eval/run")
    public R<Map<String, Object>> evalRun(@RequestParam Long runId) {
        return R.ok(evaluationService.runDetail(runId));
    }

    @GetMapping("/eval/runs")
    public R<Object> evalRuns(@RequestParam Long agentId) {
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

    private static Long longVal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intVal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
