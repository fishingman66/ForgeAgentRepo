package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsBadcase;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import com.forgeagent.customerservice.mapper.CsBadcaseMapper;
import com.forgeagent.customerservice.mapper.CsKnowledgeAnswerMapper;
import com.forgeagent.customerservice.mapper.CsSeatSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * badcase 处置(P1,差距分析 5.4)。
 *
 * <p>此前「发现问题」就断了:低质推荐能查到、零命中能查到,但没人决定该怎么办。这里补三个出口:</p>
 * <ul>
 *   <li>{@code ADOPTED_KB} 采纳为知识——用 badcase 问题生成待审 FAQ,运营补答案后生效;</li>
 *   <li>{@code ADOPTED_EVAL} 标记为评测用例——只做标记与导出,评测本身在平台侧,不重造;</li>
 *   <li>{@code IGNORED} 忽略——判断为无效提问或不该由 AI 回答。</li>
 * </ul>
 *
 * <p>来源四类:坐席忽略推荐、零命中、敏感词拦截、(预留)CSAT 低分。带去重:同来源同推荐只记一条。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsBadcaseService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int QUESTION_LIMIT = 500;
    private static final int ANSWER_LIMIT = 1000;

    private final CsBadcaseMapper badcaseMapper;
    private final CsSeatSuggestionMapper suggestionMapper;
    private final CsKnowledgeAnswerMapper answerMapper;
    /** D2:评测回流(直写平台评测集) */
    private final CsEvalFeedbackService evalFeedbackService;

    /** 通用记录入口;同来源同推荐已存在则跳过,避免刷屏。 */
    public void record(Long workspaceId, String source, Long conversationId, Long ticketId,
                       Long suggestionId, String question, String answer) {
        if (workspaceId == null || source == null) {
            return;
        }
        try {
            if (suggestionId != null) {
                Long exists = badcaseMapper.selectCount(new LambdaQueryWrapper<CsBadcase>()
                    .eq(CsBadcase::getWorkspaceId, workspaceId)
                    .eq(CsBadcase::getSource, source)
                    .eq(CsBadcase::getSuggestionId, suggestionId));
                if (exists != null && exists > 0) {
                    return;
                }
            }
            CsBadcase row = new CsBadcase();
            row.setWorkspaceId(workspaceId);
            row.setSource(source);
            row.setConversationId(conversationId);
            row.setTicketId(ticketId);
            row.setSuggestionId(suggestionId);
            row.setQuestion(truncate(question, QUESTION_LIMIT));
            row.setAnswer(truncate(answer, ANSWER_LIMIT));
            row.setStatus("PENDING");
            row.setExported("0");
            row.setCreateTime(new Date());
            row.setUpdateTime(new Date());
            badcaseMapper.insert(row);
        } catch (Exception e) {
            log.warn("[CS] badcase 记录失败 source={}: {}", source, e.getMessage());
        }
    }

    /** 坐席忽略推荐 → 一条 badcase,问题取触发原文、答案取首条候选。 */
    public void recordSeatIgnore(Long suggestionId, Long workspaceId) {
        if (suggestionId == null) {
            return;
        }
        CsSeatSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null) {
            return;
        }
        record(workspaceId, "SEAT_IGNORE", suggestion.getConversationId(), suggestion.getTicketId(),
            suggestionId, suggestion.getTriggerText(), firstItemContent(suggestion.getItems()));
    }

    public List<com.forgeagent.customerservice.domain.vo.CsBadcaseVo> list(Long workspaceId, String status, int limit) {
        LambdaQueryWrapper<CsBadcase> query = new LambdaQueryWrapper<CsBadcase>()
            .eq(CsBadcase::getWorkspaceId, workspaceId);
        if (status != null && !status.isBlank()) {
            query.eq(CsBadcase::getStatus, status);
        }
        return com.forgeagent.common.core.utils.MapstructUtils.convert(badcaseMapper.selectList(query
            .orderByDesc(CsBadcase::getId)
            .last("limit " + Math.max(1, Math.min(200, limit)))), com.forgeagent.customerservice.domain.vo.CsBadcaseVo.class);
    }

    /** 出口一:采纳为知识(生成待审 FAQ,答案留给运营补)。 */
    public com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo adoptKnowledge(Long id, Long workspaceId, String operator) {
        CsBadcase badcase = require(id, workspaceId);
        String question = badcase.getQuestion();
        if (question == null || question.isBlank()) {
            throw new ServiceException("该 badcase 没有问题文本，无法转成知识");
        }
        // 同问题可能已由知识缺口生成过待审答案,直接复用,不重复插入
        String hash = CsKnowledgeAnswerService.hash(question);
        CsKnowledgeAnswer existing = answerMapper.selectOne(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getQuestionHash, hash)
            .last("limit 1"));
        if (existing != null) {
            finish(badcase, "ADOPTED_KB", operator);
            return com.forgeagent.common.core.utils.MapstructUtils.convert(existing, com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
        }
        CsKnowledgeAnswer answer = new CsKnowledgeAnswer();
        answer.setWorkspaceId(workspaceId);
        answer.setQuestion(question);
        answer.setQuestionHash(hash);
        answer.setAnswer("");
        answer.setSource("badcase");
        answer.setSourceRefId(id);
        answer.setStatus("PENDING");
        answer.setCreateTime(new Date());
        answerMapper.insert(answer);
        finish(badcase, "ADOPTED_KB", operator);
        return com.forgeagent.common.core.utils.MapstructUtils.convert(answer, com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
    }

    /**
     * 出口二:标记为评测用例。
     *
     * <p>D2 起直接写平台评测集 {@code agent_eval_case}(待确认态),
     * 不再依赖「导出 JSON → 运营手工导入」的搬运;幂等由唯一键兜住。</p>
     */
    public com.forgeagent.customerservice.domain.vo.CsBadcaseVo adoptEval(Long id, Long workspaceId, String operator) {
        CsBadcase badcase = require(id, workspaceId);
        if (badcase.getQuestion() == null || badcase.getQuestion().isBlank()) {
            throw new ServiceException("该 badcase 没有问题文本，无法作为评测用例");
        }
        Long caseId = evalFeedbackService.pushBadcase(badcase);
        finish(badcase, "ADOPTED_EVAL", operator);
        if (caseId != null) {
            badcaseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CsBadcase>()
                .eq(CsBadcase::getId, id)
                .set(CsBadcase::getExported, "1"));
        }
        log.info("[CSP][D2] badcase {} 已推送评测集, caseId={}", id, caseId);
        return com.forgeagent.common.core.utils.MapstructUtils.convert(badcaseMapper.selectById(id), com.forgeagent.customerservice.domain.vo.CsBadcaseVo.class);
    }

    /** 出口三:忽略。 */
    public com.forgeagent.customerservice.domain.vo.CsBadcaseVo ignore(Long id, Long workspaceId, String operator, String note) {
        CsBadcase badcase = require(id, workspaceId);
        finish(badcase, "IGNORED", operator);
        if (note != null && !note.isBlank()) {
            badcase.setNote(truncate(note, 500));
            badcaseMapper.updateById(badcase);
        }
        return com.forgeagent.common.core.utils.MapstructUtils.convert(badcaseMapper.selectById(id), com.forgeagent.customerservice.domain.vo.CsBadcaseVo.class);
    }

    /**
     * 兼容出口:D2 之前「已标记但未推送」的历史 badcase 在这里补推一次。
     *
     * <p>新数据在 {@link #adoptEval} 时已直写评测集,所以正常情况下这里返回空列表。
     * 返回值仍保留原结构(含 caseId)供旧调用方使用,不再产出「待人工导入的 JSON」。</p>
     */
    public List<com.forgeagent.customerservice.domain.vo.CsExportEvalRowVo> exportEval(Long workspaceId, int limit) {
        List<CsBadcase> rows = badcaseMapper.selectList(new LambdaQueryWrapper<CsBadcase>()
            .eq(CsBadcase::getWorkspaceId, workspaceId)
            .eq(CsBadcase::getStatus, "ADOPTED_EVAL")
            .eq(CsBadcase::getExported, "0")
            .orderByAsc(CsBadcase::getId)
            .last("limit " + Math.max(1, Math.min(500, limit))));
        List<com.forgeagent.customerservice.domain.vo.CsExportEvalRowVo> payload = new ArrayList<>();
        for (CsBadcase row : rows) {
            Long caseId;
            try {
                caseId = evalFeedbackService.pushBadcase(row);
            } catch (Exception e) {
                log.warn("[CSP][D2] badcase {} 补推评测集失败: {}", row.getId(), e.getMessage());
                continue;
            }
            com.forgeagent.customerservice.domain.vo.CsExportEvalRowVo item = new com.forgeagent.customerservice.domain.vo.CsExportEvalRowVo();
            item.setCaseId(caseId);
            item.setBadcaseId(row.getId());
            item.setSource(row.getSource());
            item.setQuestion(row.getQuestion());
            item.setExpectedAnswer(row.getAnswer());
            item.setConversationId(row.getConversationId());
            payload.add(item);
            badcaseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CsBadcase>()
                .eq(CsBadcase::getId, row.getId())
                .set(CsBadcase::getExported, "1")
                .set(CsBadcase::getUpdateTime, new Date()));
        }
        return payload;
    }

    private CsBadcase require(Long id, Long workspaceId) {
        CsBadcase badcase = badcaseMapper.selectById(id);
        if (badcase == null || !badcase.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("badcase 不存在");
        }
        return badcase;
    }

    private void finish(CsBadcase badcase, String status, String operator) {
        badcase.setStatus(status);
        badcase.setOperator(operator);
        badcase.setUpdateTime(new Date());
        badcaseMapper.updateById(badcase);
    }

    private String firstItemContent(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> items = MAPPER.readValue(itemsJson,
                new TypeReference<List<Map<String, Object>>>() {
                });
            for (Map<String, Object> item : items) {
                Object content = item.get("content");
                if (content != null && !String.valueOf(content).isBlank()) {
                    return String.valueOf(content);
                }
            }
        } catch (Exception e) {
            log.debug("[CS] badcase 候选解析失败: {}", e.getMessage());
        }
        return null;
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
