package com.forgeagent.customerservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsCsat;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.domain.CsMetricDaily;
import com.forgeagent.customerservice.domain.CsQualityCheck;
import com.forgeagent.customerservice.domain.CsQualityRule;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.service.CsKnowledgeAnswerService;
import com.forgeagent.customerservice.service.CsMetricService;
import com.forgeagent.customerservice.service.CsatService;
import com.forgeagent.customerservice.service.CsQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服运营端点(C2):满意度 / 质检 / 知识答案 / 指标 / 数据飞轮。
 * 登录态由 Sa-Token 保证,数据边界取当前坐席所属空间。
 */
@SaCheckLogin
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/customerservice")
public class CsOperationController extends BaseController {

    private final CsatService csatService;
    private final CsQualityService qualityService;
    private final CsKnowledgeAnswerService knowledgeAnswerService;
    private final CsMetricService metricService;
    private final CsSeatMapper seatMapper;
    private final AgentMessageMapper messageMapper;
    /** C4:合规审计 / 留存擦除 / 客服模板。 */
    private final com.forgeagent.customerservice.compliance.CsComplianceService complianceService;
    private final com.forgeagent.customerservice.service.CsAgentTemplateService templateService;
    /** P0:敏感词词库(平台级 + 本空间)。 */
    private final com.forgeagent.customerservice.service.CsSensitiveWordService sensitiveWordService;
    /** P1:语义缓存运营查看与失效。 */
    private final com.forgeagent.customerservice.service.CsSemanticCacheService semanticCacheService;
    /** P1:知识缺口与 badcase 处置。 */
    private final com.forgeagent.customerservice.service.CsKnowledgeGapService knowledgeGapService;
    private final com.forgeagent.customerservice.service.CsBadcaseService badcaseService;
    /** P3:多 Agent 意图路由。 */
    private final com.forgeagent.customerservice.service.CsAgentRouteService agentRouteService;
    /** D2:评测回流用例(直写平台评测集 + 人工确认)。 */
    private final com.forgeagent.customerservice.service.CsEvalFeedbackService evalFeedbackService;
    /** B3 FAQ 答案自动生成。 */
    private final com.forgeagent.customerservice.service.CsFaqGenerateService faqGenerateService;

    @GetMapping("/csat")
    public R<List<com.forgeagent.customerservice.domain.vo.CsCsatVo>> csatList() {
        return R.ok(csatService.list(currentWorkspaceId()));
    }

    /** 多 Agent 路由(P3):channelCode 为空=空间级,对所有渠道生效。 */
    @GetMapping("/agent-routes")
    public R<List<com.forgeagent.customerservice.domain.vo.CsAgentRouteVo>> agentRoutes(
        @RequestParam(required = false) String channelCode) {
        return R.ok(agentRouteService.list(currentWorkspaceId(), channelCode));
    }

    @PostMapping("/agent-routes")
    public R<com.forgeagent.customerservice.domain.vo.CsAgentRouteVo> saveAgentRoute(
        @Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsAgentRouteSaveBo bo) {
        return R.ok(agentRouteService.save(bo, currentWorkspaceId()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/agent-routes/{id}")
    public R<Boolean> removeAgentRoute(@PathVariable Long id) {
        return R.ok(agentRouteService.remove(id, currentWorkspaceId()));
    }

    /** 知识缺口(P1):默认只看 OPEN,按命中次数倒序。 */
    @GetMapping("/knowledge-gaps")
    public R<List<com.forgeagent.customerservice.domain.vo.CsKnowledgeGapVo>> knowledgeGaps(
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return R.ok(knowledgeGapService.list(currentWorkspaceId(), status, limit));
    }

    /** 一键把缺口转成待审答案(问题即缺口原文,答案留空待运营补)。 */
    @PostMapping("/knowledge-gaps/{id}/create-answer")
    public R<com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo> createGapAnswer(@PathVariable Long id) {
        return R.ok(knowledgeGapService.createAnswer(id, currentWorkspaceId()));
    }

    @PostMapping("/knowledge-gaps/{id}/ignore")
    public R<Boolean> ignoreGap(@PathVariable Long id) {
        return R.ok(knowledgeGapService.ignore(id, currentWorkspaceId()));
    }

    /** 改进单(P1 badcase):默认待处理,三个出口分别采纳为知识 / 标记评测 / 忽略。 */
    @GetMapping("/improvements")
    public R<List<com.forgeagent.customerservice.domain.vo.CsBadcaseVo>> improvements(
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return R.ok(badcaseService.list(currentWorkspaceId(), status, limit));
    }

    @PostMapping("/improvements/{id}/adopt-kb")
    public R<com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo> adoptKb(@PathVariable Long id) {
        return R.ok(badcaseService.adoptKnowledge(id, currentWorkspaceId(),
            String.valueOf(LoginHelper.getUserId())));
    }

    @PostMapping("/improvements/{id}/adopt-eval")
    public R<com.forgeagent.customerservice.domain.vo.CsBadcaseVo> adoptEval(@PathVariable Long id) {
        return R.ok(badcaseService.adoptEval(id, currentWorkspaceId(),
            String.valueOf(LoginHelper.getUserId())));
    }

    @PostMapping("/improvements/{id}/ignore")
    public R<com.forgeagent.customerservice.domain.vo.CsBadcaseVo> ignoreImprovement(
        @PathVariable Long id, @RequestBody(required = false) com.forgeagent.customerservice.domain.bo.CsNoteBo bo) {
        return R.ok(badcaseService.ignore(id, currentWorkspaceId(),
            String.valueOf(LoginHelper.getUserId()), bo == null ? null : bo.getNote()));
    }

    /** 导出已标记的评测样本(JSON),由运营导入平台评测集——客服域不自建评测引擎。 */
    @GetMapping("/improvements/export-eval")
    public R<List<com.forgeagent.customerservice.domain.vo.CsExportEvalRowVo>> exportEval(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(badcaseService.exportEval(currentWorkspaceId(), limit));
    }

    /** 语义缓存(P1):按「空间 + 渠道」分区查看;知识更新后可清空一个分区。 */    @GetMapping("/semantic-cache")
    public R<List<com.forgeagent.customerservice.domain.vo.CsSemanticCacheVo>> semanticCache(
        @RequestParam String channelCode, @RequestParam(defaultValue = "50") int limit) {
        return R.ok(semanticCacheService.list(currentWorkspaceId(), channelCode, limit));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/semantic-cache/{id}")
    public R<Boolean> removeSemanticCache(@PathVariable Long id) {
        return R.ok(semanticCacheService.remove(id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/semantic-cache")
    public R<Integer> clearSemanticCache(@RequestParam String channelCode) {
        return R.ok(semanticCacheService.clearScope(currentWorkspaceId(), channelCode));
    }

    /** 敏感词词库(P0):平台级只读,空间级可增删改;改完立即失效本地快照。 */    @GetMapping("/sensitive-words")
    public R<List<com.forgeagent.customerservice.domain.vo.CsSensitiveWordVo>> sensitiveWords() {
        return R.ok(sensitiveWordService.list(currentWorkspaceId()));
    }

    @PostMapping("/sensitive-words")
    public R<com.forgeagent.customerservice.domain.vo.CsSensitiveWordVo> saveSensitiveWord(
        @Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsSensitiveWordSaveBo bo) {
        return R.ok(sensitiveWordService.save(bo, currentWorkspaceId()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/sensitive-words/{id}")
    public R<Boolean> removeSensitiveWord(@PathVariable Long id) {
        return R.ok(sensitiveWordService.remove(id));
    }

    @GetMapping("/csat/stats")
    public R<com.forgeagent.customerservice.domain.vo.CsCsatStatsVo> csatStats(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return R.ok(csatService.stats(currentWorkspaceId(), parseDate(from), parseDate(to)));
    }

    @GetMapping("/quality/rules")
    public R<List<com.forgeagent.customerservice.domain.vo.CsQualityRuleVo>> qualityRules() {
        return R.ok(qualityService.rules(currentWorkspaceId()));
    }

    @PostMapping("/quality/rules")
    public R<com.forgeagent.customerservice.domain.vo.CsQualityRuleVo> saveQualityRule(@Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsQualityRuleSaveBo bo) {
        return R.ok(qualityService.saveRule(bo, currentWorkspaceId()));
    }

    @GetMapping("/quality/checks")
    public R<List<com.forgeagent.customerservice.domain.vo.CsQualityCheckVo>> qualityChecks() {
        return R.ok(qualityService.checks(currentWorkspaceId()));
    }

    @PostMapping("/quality/checks/{id}/review")
    public R<com.forgeagent.customerservice.domain.vo.CsQualityCheckVo> reviewQuality(@PathVariable Long id, @RequestParam String status) {
        return R.ok(qualityService.review(id, status));
    }

    @GetMapping("/knowledge-answers")
    public R<List<com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo>> answers(@RequestParam(required = false) String status) {
        return R.ok(knowledgeAnswerService.list(currentWorkspaceId(), status));
    }

    @PostMapping("/knowledge-answers")
    public R<com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo> saveAnswer(@Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsKnowledgeAnswerSaveBo bo) {
        return R.ok(knowledgeAnswerService.save(bo, currentWorkspaceId()));
    }

    @PostMapping("/knowledge-answers/{id}/audit")
    public R<com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo> auditAnswer(@PathVariable Long id,
                                            @RequestParam String status,
                                            @RequestParam(required = false) String note) {
        return R.ok(knowledgeAnswerService.audit(id, status, LoginHelper.getUserId(), note));
    }

    /** 数据飞轮:最近的零命中查询批量转成待审答案。 */
    @PostMapping("/knowledge-answers/from-zero-hit")
    public R<Integer> fromZeroHit(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(knowledgeAnswerService.fromZeroHit(currentWorkspaceId(), limit));
    }

    /** B3 FAQ 答案自动生成:对待审空答案生成候选+引用,仍 PENDING 待人工审核。 */
    @PostMapping("/knowledge-answers/generate")
    public R<com.forgeagent.customerservice.service.CsFaqGenerateService.GenerateResult> generateFaq(
        @RequestParam(defaultValue = "20") int limit) {
        return R.ok(faqGenerateService.generate(currentWorkspaceId(), limit));
    }

    /** B3 成本估算:不实际生成,返回待审数与预估费用。 */
    @GetMapping("/knowledge-answers/generate/estimate")
    public R<com.forgeagent.customerservice.service.CsFaqGenerateService.GenerateResult> estimateFaq(
        @RequestParam(defaultValue = "20") int limit) {
        return R.ok(faqGenerateService.estimate(currentWorkspaceId(), limit));
    }

    @PostMapping("/knowledge-answers/expire")
    public R<Integer> expireAnswers() {
        return R.ok(knowledgeAnswerService.expire(currentWorkspaceId()));
    }

    @GetMapping("/metrics")
    public R<List<com.forgeagent.customerservice.domain.vo.CsMetricDailyVo>> metrics(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return R.ok(metricService.list(currentWorkspaceId(), parseDate(from), parseDate(to)));
    }

    @PostMapping("/metrics/rebuild")
    public R<java.util.List<com.forgeagent.customerservice.domain.vo.CsMetricDailyVo>> rebuildMetrics(@RequestParam(required = false) String statDate) {
        Date day = statDate == null || statDate.isBlank()
            ? new Date() : parseDate(statDate);
        return R.ok(metricService.rebuild(currentWorkspaceId(), day));
    }

    /** 消息级 like/dislike;点踩生成待审答案进入数据飞轮。 */
    @PostMapping("/feedback/{messageId}")
    public R<Boolean> feedback(@PathVariable Long messageId, @RequestParam String feedback) {
        if (!"like".equals(feedback) && !"dislike".equals(feedback) && !"".equals(feedback)) {
            throw new ServiceException("反馈值无效");
        }
        messageMapper.update(null, new LambdaUpdateWrapper<AgentMessage>()
            .eq(AgentMessage::getId, messageId)
            .set(AgentMessage::getFeedback, feedback)
            .set(AgentMessage::getFeedbackTime, new Date()));
        if ("dislike".equals(feedback)) {
            AgentMessage msg = messageMapper.selectById(messageId);
            if (msg != null && msg.getContent() != null && !msg.getContent().isBlank()) {
                CsKnowledgeAnswer answer = new CsKnowledgeAnswer();
                answer.setWorkspaceId(currentWorkspaceId());
                answer.setQuestion(msg.getContent().substring(0, Math.min(500, msg.getContent().length())));
                answer.setAnswer("");
                answer.setSource("badcase");
                answer.setSourceRefId(messageId);
                knowledgeAnswerService.save(answer);
            }
        }
        return R.ok(true);
    }

    /** 负反馈消息列表(badcase 入口)。 */
    @GetMapping("/badcases")
    public R<List<AgentMessage>> badcases() {
        return R.ok(messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getFeedback, "dislike")
            .orderByDesc(AgentMessage::getId).last("limit 100")));
    }

    /** C4:合规审计 / 擦除 / 留存清理 / 客服模板。 */
    @GetMapping("/compliance/audit")
    public R<List<com.forgeagent.customerservice.domain.vo.CsAuditLogVo>> audit() {
        return R.ok(complianceService.listAudit(currentWorkspaceId()));
    }

    @PostMapping("/compliance/erase/{cid}")
    public R<Integer> erase(@PathVariable Long cid) {
        return R.ok(complianceService.eraseConversation(cid, "SEAT", String.valueOf(LoginHelper.getUserId())));
    }

    @PostMapping("/compliance/purge")
    public R<Integer> purge() {
        return R.ok(complianceService.purgeExpired());
    }

    @GetMapping("/templates")
    public R<List<com.forgeagent.customerservice.domain.vo.CsTemplateItemVo>> templates() {
        return R.ok(templateService.templates());
    }

    @PostMapping("/templates/{code}/apply")
    public R<com.forgeagent.customerservice.domain.vo.CsChannelVo> applyTemplate(@PathVariable String code, @RequestParam String channelCode) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(templateService.apply(code, channelCode), com.forgeagent.customerservice.domain.vo.CsChannelVo.class));
    }

    private Long currentWorkspaceId() {
        CsSeat seat = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, LoginHelper.getUserId()).last("limit 1"));
        return seat == null ? -1L : seat.getWorkspaceId();
    }

    // ---------------------------------------------------------------- D2 评测回流

    /** 回流用例列表:默认只看待确认(reviewStatus=0),传空串看全部 */
    @GetMapping("/eval/cases")
    public R<List<com.forgeagent.customerservice.domain.vo.CsEvalCaseRowVo>> evalCases(@RequestParam(required = false) String reviewStatus,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return R.ok(evalFeedbackService.listCases(currentWorkspaceId(),
            reviewStatus == null ? "0" : reviewStatus, limit));
    }

    /** 待确认用例数(运营页角标) */
    @GetMapping("/eval/pending-count")
    public R<Integer> evalPendingCount() {
        return R.ok(evalFeedbackService.pendingCount(currentWorkspaceId()));
    }

    /** 确认或驳回一条回流用例;只有已确认的用例会参与评测运行 */
    @PostMapping("/eval/cases/{id}/review")
    public R<Void> reviewEvalCase(@PathVariable Long id,
                                  @RequestBody(required = false) com.forgeagent.customerservice.domain.bo.CsEvalReviewBo bo) {
        evalFeedbackService.review(id, currentWorkspaceId(), bo != null && Boolean.TRUE.equals(bo.getApprove()));
        return R.ok();
    }

    /** 采样口径说明(比例 / 最少访客轮次 / 评测集名) */
    @GetMapping("/eval/sample-info")
    public R<com.forgeagent.customerservice.domain.vo.CsEvalSampleInfoVo> evalSampleInfo() {
        return R.ok(evalFeedbackService.sampleInfo());
    }

    private static Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            throw new ServiceException("日期格式须为 yyyy-MM-dd");
        }
    }
}
