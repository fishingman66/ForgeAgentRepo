package com.forgeagent.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.rag.domain.AgentKbEvalCase;
import com.forgeagent.rag.domain.AgentKbEvalRun;
import com.forgeagent.rag.service.KbEvalService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索评测 Controller:维护评测问题集,批量运行检索并统计命中率与 MRR
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/kb/eval")
public class AgentKbEvalController extends BaseController {

    private final KbEvalService evalService;

    /**
     * 评测用例列表
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/cases")
    public R<List<AgentKbEvalCase>> cases(@NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(evalService.listCases(knowledgeId));
    }

    /**
     * 新增评测用例
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库评测用例", businessType = BusinessType.INSERT)
    @PostMapping("/case")
    public R<Long> addCase(@RequestBody AgentKbEvalCase evalCase) {
        return R.ok(evalService.saveCase(evalCase));
    }

    /**
     * 删除评测用例
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库评测用例", businessType = BusinessType.DELETE)
    @DeleteMapping("/case/{id}")
    public R<Void> deleteCase(@NotNull(message = "用例ID不能为空") @PathVariable Long id) {
        evalService.deleteCase(id);
        return R.ok();
    }

    /**
     * 运行评测:批量执行检索并统计命中率/MRR,返回汇总与逐用例明细
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库检索评测", businessType = BusinessType.OTHER)
    @PostMapping("/run")
    public R<Map<String, Object>> run(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                      Integer topK) {
        return R.ok(evalService.runEvaluation(knowledgeId, topK));
    }

    /**
     * 评测运行历史
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/runs")
    public R<List<AgentKbEvalRun>> runs(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                        Integer limit) {
        return R.ok(evalService.listRuns(knowledgeId, limit));
    }

    /**
     * 评测运行历史(分页)
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/runs/page")
    public R<PageResult<AgentKbEvalRun>> runsPage(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                                  PageQuery pageQuery) {
        return R.ok(evalService.pageRuns(knowledgeId, pageQuery));
    }

    /**
     * 当前语料指纹(R3-4):门禁可比性的依据之一
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/fingerprint")
    public R<String> fingerprint(@NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(evalService.corpusFingerprint(knowledgeId));
    }

    /**
     * 双跑对比(R3-4):按用例交集重算两侧指标后判决,结果写回候选 run
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库评测对比", businessType = BusinessType.OTHER)
    @PostMapping("/compare")
    public R<Map<String, Object>> compare(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                          @NotNull(message = "基线运行ID不能为空") Long baselineRunId,
                                          @NotNull(message = "候选运行ID不能为空") Long candidateRunId) {
        return R.ok(evalService.compare(knowledgeId, baselineRunId, candidateRunId));
    }

    /**
     * 重建门禁检查(R3-4):最近两次运行的判决;BLOCKED 时重建入口会拒绝
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/gate")
    public R<Map<String, Object>> gate(@NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(evalService.gateCheck(knowledgeId));
    }
}
