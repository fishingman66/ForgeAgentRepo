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
import com.forgeagent.rag.domain.bo.KbEvalCaseBo;
import com.forgeagent.rag.domain.vo.AgentKbEvalCaseVo;
import com.forgeagent.rag.domain.vo.AgentKbEvalRunVo;
import com.forgeagent.rag.domain.vo.KbEvalCompareVo;
import com.forgeagent.rag.domain.vo.KbEvalGateVo;
import com.forgeagent.rag.domain.vo.KbEvalRunResultVo;
import com.forgeagent.rag.service.KbEvalService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public R<List<AgentKbEvalCaseVo>> cases(@NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(evalService.listCases(knowledgeId));
    }

    /**
     * 新增评测用例
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库评测用例", businessType = BusinessType.INSERT)
    @PostMapping("/case")
    public R<Long> addCase(@Validated(AddGroup.class) @RequestBody KbEvalCaseBo bo) {
        return R.ok(evalService.saveCase(toEntity(bo)));
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
    public R<KbEvalRunResultVo> run(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                    Integer topK) {
        return R.ok(evalService.runEvaluation(knowledgeId, topK));
    }

    /**
     * 评测运行历史
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/runs")
    public R<List<AgentKbEvalRunVo>> runs(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                          Integer limit) {
        return R.ok(evalService.listRuns(knowledgeId, limit));
    }

    /**
     * 评测运行历史(分页)
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/runs/page")
    public R<PageResult<AgentKbEvalRunVo>> runsPage(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
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
    public R<KbEvalCompareVo> compare(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                      @NotNull(message = "基线运行ID不能为空") Long baselineRunId,
                                      @NotNull(message = "候选运行ID不能为空") Long candidateRunId) {
        return R.ok(evalService.compare(knowledgeId, baselineRunId, candidateRunId));
    }

    /**
     * 重建门禁检查(R3-4):最近两次运行的判决;BLOCKED 时重建入口会拒绝
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/gate")
    public R<KbEvalGateVo> gate(@NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(evalService.gateCheck(knowledgeId));
    }

    /**
     * Bo → 实体。手写映射而不走 MapstructUtils:
     * 后者静态初始化依赖 Spring 容器,纯 Mockito 场景下类加载即失败(docs/12 第八节)。
     */
    private AgentKbEvalCase toEntity(KbEvalCaseBo bo) {
        AgentKbEvalCase entity = new AgentKbEvalCase();
        entity.setId(bo.getId());
        entity.setKnowledgeId(bo.getKnowledgeId());
        entity.setQuestion(bo.getQuestion());
        entity.setExpectedDocName(bo.getExpectedDocName());
        entity.setExpectedKeywords(bo.getExpectedKeywords());
        entity.setEvidences(bo.getEvidences());
        entity.setStatus(bo.getStatus());
        entity.setSource(bo.getSource());
        entity.setSourceId(bo.getSourceId());
        entity.setDatasetRevision(bo.getDatasetRevision());
        entity.setRemark(bo.getRemark());
        return entity;
    }
}
