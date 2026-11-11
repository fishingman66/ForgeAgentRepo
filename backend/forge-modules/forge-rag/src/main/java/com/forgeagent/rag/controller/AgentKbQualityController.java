package com.forgeagent.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.rag.service.KbQualityIssueService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 知识库质量问题 Controller(R3-5):零命中 → 用例 → 质量问题闭环的登记与流转
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/kb/quality")
public class AgentKbQualityController extends BaseController {

    private final KbQualityIssueService qualityService;

    /**
     * 登记质量问题(按 knowledgeId+source+queryHash 去重,重复返回已有ID)
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库质量问题登记", businessType = BusinessType.INSERT)
    @PostMapping("/register")
    public R<Long> register(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                            @NotNull(message = "查询原文不能为空") String queryText,
                            String reason, String source, Long sourceId, String note) {
        return R.ok(qualityService.register(knowledgeId, queryText, reason, source, sourceId, note));
    }

    /**
     * 分页查询
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/page")
    public R<PageResult<com.forgeagent.rag.domain.vo.AgentKbQualityIssueVo>> page(
        @NotNull(message = "知识库ID不能为空") Long knowledgeId, String status, PageQuery pageQuery) {
        return R.ok(qualityService.page(knowledgeId, status, pageQuery));
    }

    /**
     * 状态流转:NEW → IN_PROGRESS → RESOLVED(reopen 走 RESOLVED → IN_PROGRESS)
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库质量问题流转", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@NotNull(message = "问题ID不能为空") @PathVariable Long id,
                                @NotNull(message = "目标状态不能为空") String target,
                                String note, Integer revision) {
        qualityService.changeStatus(id, target, note, revision,
            com.forgeagent.common.satoken.utils.LoginHelper.getUsername());
        return R.ok();
    }

    /**
     * 关联评测用例
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库质量问题关联用例", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/link-case")
    public R<Void> linkCase(@NotNull(message = "问题ID不能为空") @PathVariable Long id,
                            @NotNull(message = "用例ID不能为空") Long caseId) {
        qualityService.linkCase(id, caseId);
        return R.ok();
    }

    /**
     * 从检索命中生成评测用例(R3-5):query 作问题,chunk 文本作证据 span,source=DEBUG_PAGE
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库调试页生成用例", businessType = BusinessType.INSERT)
    @PostMapping("/case-from-hit")
    public R<Long> createCaseFromHit(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                                     @NotNull(message = "评测问题不能为空") String question,
                                     @NotNull(message = "证据文档不能为空") Long documentId,
                                     Long chunkId, String span) {
        return R.ok(qualityService.createCaseFromHit(knowledgeId, question, documentId, chunkId, span));
    }
}
