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

    @GetMapping("/csat")
    public R<List<CsCsat>> csatList() {
        return R.ok(csatService.list(currentWorkspaceId()));
    }

    /** 多 Agent 路由(P3):channelCode 为空=空间级,对所有渠道生效。 */
    @GetMapping("/agent-routes")
    public R<List<com.forgeagent.customerservice.domain.CsAgentRoute>> agentRoutes(
        @RequestParam(required = false) String channelCode) {
        return R.ok(agentRouteService.list(currentWorkspaceId(), channelCode));
    }

    @PostMapping("/agent-routes")
    public R<com.forgeagent.customerservice.domain.CsAgentRoute> saveAgentRoute(
        @RequestBody com.forgeagent.customerservice.domain.CsAgentRoute route) {
        return R.ok(agentRouteService.save(route, currentWorkspaceId()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/agent-routes/{id}")
    public R<Boolean> removeAgentRoute(@PathVariable Long id) {
        return R.ok(agentRouteService.remove(id, currentWorkspaceId()));
    }

    /** 知识缺口(P1):默认只看 OPEN,按命中次数倒序。 */
    @GetMapping("/knowledge-gaps")
    public R<List<com.forgeagent.customerservice.domain.CsKnowledgeGap>> knowledgeGaps(
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return R.ok(knowledgeGapService.list(currentWorkspaceId(), status, limit));
    }

    /** 一键把缺口转成待审答案(问题即缺口原文,答案留空待运营补)。 */
    @PostMapping("/knowledge-gaps/{id}/create-answer")
    public R<CsKnowledgeAnswer> createGapAnswer(@PathVariable Long id) {
        return R.ok(knowledgeGapService.createAnswer(id, currentWorkspaceId()));
    }

    @PostMapping("/knowledge-gaps/{id}/ignore")
    public R<Boolean> ignoreGap(@PathVariable Long id) {
        return R.ok(knowledgeGapService.ignore(id, currentWorkspaceId()));
    }

    /** 改进单(P1 badcase):默认待处理,三个出口分别采纳为知识 / 标记评测 / 忽略。 */
    @GetMapping("/improvements")
    public R<List<com.forgeagent.customerservice.domain.CsBadcase>> improvements(
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return R.ok(badcaseService.list(currentWorkspaceId(), status, limit));
    }

    @PostMapping("/improvements/{id}/adopt-kb")
    public R<CsKnowledgeAnswer> adoptKb(@PathVariable Long id) {
        return R.ok(badcaseService.adoptKnowledge(id, currentWorkspaceId(),
            String.valueOf(LoginHelper.getUserId())));
    }

    @PostMapping("/improvements/{id}/adopt-eval")
    public R<com.forgeagent.customerservice.domain.CsBadcase> adoptEval(@PathVariable Long id) {
        return R.ok(badcaseService.adoptEval(id, currentWorkspaceId(),
            String.valueOf(LoginHelper.getUserId())));
    }

    @PostMapping("/improvements/{id}/ignore")
    public R<com.forgeagent.customerservice.domain.CsBadcase> ignoreImprovement(
        @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        return R.ok(badcaseService.ignore(id, currentWorkspaceId(),
            String.valueOf(LoginHelper.getUserId()), note));
    }

    /** 导出已标记的评测样本(JSON),由运营导入平台评测集——客服域不自建评测引擎。 */
    @GetMapping("/improvements/export-eval")
    public R<List<Map<String, Object>>> exportEval(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(badcaseService.exportEval(currentWorkspaceId(), limit));
    }

    /** 语义缓存(P1):按「空间 + 渠道」分区查看;知识更新后可清空一个分区。 */    @GetMapping("/semantic-cache")
    public R<List<com.forgeagent.customerservice.domain.CsSemanticCache>> semanticCache(
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
    public R<List<com.forgeagent.customerservice.domain.CsSensitiveWord>> sensitiveWords() {
        return R.ok(sensitiveWordService.list(currentWorkspaceId()));
    }

    @PostMapping("/sensitive-words")
    public R<com.forgeagent.customerservice.domain.CsSensitiveWord> saveSensitiveWord(
        @RequestBody com.forgeagent.customerservice.domain.CsSensitiveWord word) {
        return R.ok(sensitiveWordService.save(word, currentWorkspaceId()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/sensitive-words/{id}")
    public R<Boolean> removeSensitiveWord(@PathVariable Long id) {
        return R.ok(sensitiveWordService.remove(id));
    }

    @GetMapping("/csat/stats")
    public R<Map<String, Object>> csatStats(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return R.ok(csatService.stats(currentWorkspaceId(), parseDate(from), parseDate(to)));
    }

    @GetMapping("/quality/rules")
    public R<List<CsQualityRule>> qualityRules() {
        return R.ok(qualityService.rules(currentWorkspaceId()));
    }

    @PostMapping("/quality/rules")
    public R<CsQualityRule> saveQualityRule(@RequestBody CsQualityRule rule) {
        rule.setWorkspaceId(currentWorkspaceId());
        return R.ok(qualityService.saveRule(rule));
    }

    @GetMapping("/quality/checks")
    public R<List<CsQualityCheck>> qualityChecks() {
        return R.ok(qualityService.checks(currentWorkspaceId()));
    }

    @PostMapping("/quality/checks/{id}/review")
    public R<CsQualityCheck> reviewQuality(@PathVariable Long id, @RequestParam String status) {
        return R.ok(qualityService.review(id, status));
    }

    @GetMapping("/knowledge-answers")
    public R<List<CsKnowledgeAnswer>> answers(@RequestParam(required = false) String status) {
        return R.ok(knowledgeAnswerService.list(currentWorkspaceId(), status));
    }

    @PostMapping("/knowledge-answers")
    public R<CsKnowledgeAnswer> saveAnswer(@RequestBody CsKnowledgeAnswer answer) {
        answer.setWorkspaceId(currentWorkspaceId());
        return R.ok(knowledgeAnswerService.save(answer));
    }

    @PostMapping("/knowledge-answers/{id}/audit")
    public R<CsKnowledgeAnswer> auditAnswer(@PathVariable Long id,
                                            @RequestParam String status,
                                            @RequestParam(required = false) String note) {
        return R.ok(knowledgeAnswerService.audit(id, status, LoginHelper.getUserId(), note));
    }

    /** 数据飞轮:最近的零命中查询批量转成待审答案。 */
    @PostMapping("/knowledge-answers/from-zero-hit")
    public R<Integer> fromZeroHit(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(knowledgeAnswerService.fromZeroHit(currentWorkspaceId(), limit));
    }

    @PostMapping("/knowledge-answers/expire")
    public R<Integer> expireAnswers() {
        return R.ok(knowledgeAnswerService.expire(currentWorkspaceId()));
    }

    @GetMapping("/metrics")
    public R<List<CsMetricDaily>> metrics(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return R.ok(metricService.list(currentWorkspaceId(), parseDate(from), parseDate(to)));
    }

    @PostMapping("/metrics/rebuild")
    public R<CsMetricDaily> rebuildMetrics(@RequestParam(required = false) String statDate) {
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
    public R<List<com.forgeagent.customerservice.domain.CsAuditLog>> audit() {
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
    public R<List<Map<String, Object>>> templates() {
        return R.ok(templateService.templates());
    }

    @PostMapping("/templates/{code}/apply")
    public R<Object> applyTemplate(@PathVariable String code, @RequestParam String channelCode) {
        return R.ok(templateService.apply(code, channelCode));
    }

    private Long currentWorkspaceId() {
        CsSeat seat = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, LoginHelper.getUserId()).last("limit 1"));
        return seat == null ? -1L : seat.getWorkspaceId();
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
