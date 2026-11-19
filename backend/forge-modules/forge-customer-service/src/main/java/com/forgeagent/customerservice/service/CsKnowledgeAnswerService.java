package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.mapper.CsKnowledgeAnswerMapper;
import com.forgeagent.rag.domain.AgentKbZeroHitLog;
import com.forgeagent.rag.mapper.AgentKbZeroHitLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * FAQ 与补录答案(C2):零命中/负反馈 → 待审答案 → 审核生效 → 生效期过期,
 * 已生效答案在访客问答时优先命中,给确定性回复而不是再调一次模型。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsKnowledgeAnswerService {

    private final CsKnowledgeAnswerMapper answerMapper;
    private final AgentKbZeroHitLogMapper zeroHitMapper;

    public List<com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo> list(Long workspaceId, String status) {
        LambdaQueryWrapper<CsKnowledgeAnswer> q = new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId);
        if (status != null && !status.isBlank()) {
            q.eq(CsKnowledgeAnswer::getStatus, status);
        }
        return com.forgeagent.common.core.utils.MapstructUtils.convert(answerMapper.selectList(q.orderByDesc(CsKnowledgeAnswer::getId)), com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
    }

    public com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo save(com.forgeagent.customerservice.domain.bo.CsKnowledgeAnswerSaveBo bo, Long workspaceId) {
        CsKnowledgeAnswer answer = com.forgeagent.common.core.utils.MapstructUtils.convert(bo, CsKnowledgeAnswer.class);
        answer.setWorkspaceId(workspaceId);
        String hash = hash(answer.getQuestion());
        answer.setQuestionHash(hash);
        answer.setStatus("PENDING");
        answer.setSource(answer.getSource() == null ? "manual" : answer.getSource());
        answer.setCreateTime(new Date());
        CsKnowledgeAnswer existing = answerMapper.selectOne(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, answer.getWorkspaceId())
            .eq(CsKnowledgeAnswer::getQuestionHash, hash).last("limit 1"));
        if (existing != null) {
            existing.setAnswer(answer.getAnswer());
            existing.setStatus("PENDING");
            existing.setSource(answer.getSource());
            existing.setSourceRefId(answer.getSourceRefId());
            existing.setAuditBy(null);
            existing.setAuditNote(null);
            answerMapper.updateById(existing);
            return com.forgeagent.common.core.utils.MapstructUtils.convert(existing, com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
        }
        answerMapper.insert(answer);
        return com.forgeagent.common.core.utils.MapstructUtils.convert(answer, com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
    }

    public com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo audit(Long id, String status, Long auditBy, String note) {
        CsKnowledgeAnswer answer = answerMapper.selectById(id);
        if (answer == null) {
            throw new ServiceException("答案不存在");
        }
        answer.setStatus(status);
        answer.setAuditBy(auditBy);
        answer.setAuditNote(note);
        if ("ACTIVE".equals(status) && answer.getEffectiveFrom() == null) {
            answer.setEffectiveFrom(new Date());
        }
        answerMapper.updateById(answer);
        return com.forgeagent.common.core.utils.MapstructUtils.convert(answer, com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
    }

    /** 当前生效答案(状态 + 生效期双重判定)。 */
    public List<CsKnowledgeAnswer> active(Long workspaceId) {
        Date now = new Date();
        return answerMapper.selectList(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getStatus, "ACTIVE")
            .and(w -> w.isNull(CsKnowledgeAnswer::getEffectiveFrom)
                .or().le(CsKnowledgeAnswer::getEffectiveFrom, now))
            .and(w -> w.isNull(CsKnowledgeAnswer::getEffectiveTo)
                .or().ge(CsKnowledgeAnswer::getEffectiveTo, now)));
    }

    /** 访客提问时优先命中的确定性答案(问题归一化后精确或包含匹配)。 */
    public CsKnowledgeAnswer match(Long workspaceId, String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalized = question.trim().toLowerCase();
        for (CsKnowledgeAnswer answer : active(workspaceId)) {
            String q = answer.getQuestion() == null ? "" : answer.getQuestion().trim().toLowerCase();
            if (q.equals(normalized) || (!q.isBlank() && normalized.contains(q))) {
                return answer;
            }
        }
        return null;
    }

    /** 数据飞轮:把最近的零命中查询批量转成待审答案(按 question_hash 去重)。 */
    public int fromZeroHit(Long workspaceId, int limit) {
        List<AgentKbZeroHitLog> logs = zeroHitMapper.selectList(
            new LambdaQueryWrapper<AgentKbZeroHitLog>()
                .orderByDesc(AgentKbZeroHitLog::getCreateTime)
                .last("limit " + Math.max(1, Math.min(100, limit))));
        int created = 0;
        List<String> seen = new ArrayList<>();
        for (AgentKbZeroHitLog log : logs) {
            String question = log.getOriginalQuery();
            if (question == null || question.isBlank()) {
                continue;
            }
            String hash = hash(question);
            if (seen.contains(hash)) {
                continue;
            }
            seen.add(hash);
            boolean exists = answerMapper.selectCount(new LambdaQueryWrapper<CsKnowledgeAnswer>()
                .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
                .eq(CsKnowledgeAnswer::getQuestionHash, hash)) > 0;
            if (exists) {
                continue;
            }
            CsKnowledgeAnswer answer = new CsKnowledgeAnswer();
            answer.setWorkspaceId(workspaceId);
            answer.setQuestion(question);
            answer.setQuestionHash(hash);
            answer.setAnswer("");
            answer.setSource("zero_hit");
            answer.setSourceRefId(log.getId());
            answer.setStatus("PENDING");
            answer.setCreateTime(new Date());
            answerMapper.insert(answer);
            created++;
        }
        return created;
    }

    /** 生效期到期的答案自动置 EXPIRED,由看板或定时任务触发。 */
    public int expire(Long workspaceId) {
        Date now = new Date();
        List<CsKnowledgeAnswer> expired = answerMapper.selectList(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getStatus, "ACTIVE")
            .isNotNull(CsKnowledgeAnswer::getEffectiveTo)
            .lt(CsKnowledgeAnswer::getEffectiveTo, now));
        for (CsKnowledgeAnswer answer : expired) {
            answer.setStatus("EXPIRED");
            answerMapper.updateById(answer);
        }
        return expired.size();
    }

    /** 内部链路(数据飞轮/负反馈)直接落实体;对外走 Bo 重载。 */
    public com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo save(CsKnowledgeAnswer answer) {
        answerMapper.insert(answer);
        return com.forgeagent.common.core.utils.MapstructUtils.convert(answer, com.forgeagent.customerservice.domain.vo.CsKnowledgeAnswerVo.class);
    }

    public static String hash(String question) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(question.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("问题摘要计算失败", e);
        }
    }
}
