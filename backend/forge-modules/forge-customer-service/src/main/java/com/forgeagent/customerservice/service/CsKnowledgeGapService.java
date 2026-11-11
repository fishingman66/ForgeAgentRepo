package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.domain.CsKnowledgeGap;
import com.forgeagent.customerservice.mapper.CsKnowledgeAnswerMapper;
import com.forgeagent.customerservice.mapper.CsKnowledgeGapMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 知识缺口(P1,差距分析 5.3):把零命中从"一条条日志"变成"可管理的待办"。
 *
 * <p>此前只有 `agent_kb_zero_hit_log` 原始记录，谁负责、多急、补没补都靠人记。
 * 这里按问题 hash 归并同类缺口，命中次数自动升级优先级，可一键转成待审答案。</p>
 *
 * <p>优先级口径：命中 ≥5 次 P0、≥3 次 P1、其余 P2。次数是「影响面」的最直接代理，
 * 不去猜业务价值——运营可以在页面上手工再调。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsKnowledgeGapService {

    private static final int P0_THRESHOLD = 5;
    private static final int P1_THRESHOLD = 3;
    /** 缺口问题长度上限(与 FAQ question 列宽一致)。 */
    private static final int QUESTION_LIMIT = 200;

    private final CsKnowledgeGapMapper gapMapper;
    private final CsKnowledgeAnswerMapper answerMapper;
    private final CsIntentService intentService;

    /** 记录一次零命中:同问题累加次数并刷新优先级,不新增行。 */
    public void record(Long workspaceId, String question) {
        if (workspaceId == null || question == null || question.isBlank()) {
            return;
        }
        try {
            String trimmed = truncate(question.trim());
            String hash = CsKnowledgeAnswerService.hash(trimmed);
            CsKnowledgeGap gap = gapMapper.selectOne(new LambdaQueryWrapper<CsKnowledgeGap>()
                .eq(CsKnowledgeGap::getWorkspaceId, workspaceId)
                .eq(CsKnowledgeGap::getQuestionHash, hash).last("limit 1"));
            Date now = new Date();
            if (gap == null) {
                CsIntentService.Match intent = intentService.classify(workspaceId, trimmed, List.of());
                gap = new CsKnowledgeGap();
                gap.setWorkspaceId(workspaceId);
                gap.setQuestionHash(hash);
                gap.setQuestion(trimmed);
                gap.setCategory(intent == null ? null : intent.code());
                gap.setHitCount(1);
                gap.setPriority(priorityOf(1));
                gap.setStatus("OPEN");
                gap.setLastHitAt(now);
                gap.setCreateTime(now);
                gap.setUpdateTime(now);
                gapMapper.insert(gap);
                return;
            }
            int hits = (gap.getHitCount() == null ? 0 : gap.getHitCount()) + 1;
            gap.setHitCount(hits);
            // 已补齐的缺口再次命中说明答案没生效或不够,回到 OPEN 让运营看到
            if ("FILLED".equals(gap.getStatus())) {
                gap.setStatus("OPEN");
            }
            gap.setPriority(priorityOf(hits));
            gap.setLastHitAt(now);
            gap.setUpdateTime(now);
            gapMapper.updateById(gap);
        } catch (Exception e) {
            log.warn("[CS] 知识缺口记录失败 question={}: {}", question, e.getMessage());
        }
    }

    /** 运营列表:默认只看 OPEN,按命中次数倒序。 */
    public List<CsKnowledgeGap> list(Long workspaceId, String status, int limit) {
        LambdaQueryWrapper<CsKnowledgeGap> query = new LambdaQueryWrapper<CsKnowledgeGap>()
            .eq(CsKnowledgeGap::getWorkspaceId, workspaceId);
        if (status != null && !status.isBlank()) {
            query.eq(CsKnowledgeGap::getStatus, status);
        }
        return gapMapper.selectList(query
            .orderByDesc(CsKnowledgeGap::getHitCount)
            .orderByDesc(CsKnowledgeGap::getId)
            .last("limit " + Math.max(1, Math.min(200, limit))));
    }

    /** 一键把缺口转成待审答案:问题即缺口原文,答案留给运营补。 */
    public CsKnowledgeAnswer createAnswer(Long gapId, Long workspaceId) {
        CsKnowledgeGap gap = gapMapper.selectById(gapId);
        if (gap == null || !gap.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("知识缺口不存在");
        }
        if ("FILLED".equals(gap.getStatus()) && gap.getAnswerId() != null) {
            CsKnowledgeAnswer existing = answerMapper.selectById(gap.getAnswerId());
            if (existing != null) {
                return existing;
            }
        }
        CsKnowledgeAnswer answer = new CsKnowledgeAnswer();
        answer.setWorkspaceId(workspaceId);
        answer.setQuestion(gap.getQuestion());
        answer.setQuestionHash(gap.getQuestionHash());
        answer.setAnswer("");
        answer.setSource("knowledge_gap");
        answer.setSourceRefId(gapId);
        answer.setStatus("PENDING");
        answer.setCreateTime(new Date());
        answerMapper.insert(answer);

        gap.setStatus("FILLED");
        gap.setAnswerId(answer.getId());
        gap.setUpdateTime(new Date());
        gapMapper.updateById(gap);
        return answer;
    }

    /** 忽略一个缺口(判断为无效提问或不该由 AI 回答)。 */
    public boolean ignore(Long gapId, Long workspaceId) {
        CsKnowledgeGap gap = gapMapper.selectById(gapId);
        if (gap == null || !gap.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("知识缺口不存在");
        }
        gap.setStatus("IGNORED");
        gap.setUpdateTime(new Date());
        gapMapper.updateById(gap);
        return true;
    }

    /** 优先级规则(纯函数,单测锁定)。 */
    public static String priorityOf(int hitCount) {
        if (hitCount >= P0_THRESHOLD) {
            return "P0";
        }
        if (hitCount >= P1_THRESHOLD) {
            return "P1";
        }
        return "P2";
    }

    private static String truncate(String text) {
        return text.length() <= QUESTION_LIMIT ? text : text.substring(0, QUESTION_LIMIT);
    }
}
