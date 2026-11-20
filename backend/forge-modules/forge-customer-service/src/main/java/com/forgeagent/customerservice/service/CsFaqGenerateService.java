package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsKnowledgeAnswerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * B3 FAQ 答案自动生成:对待审空答案问题生成候选答案 + 引用,仍进人工审核。
 *
 * <p>数据飞轮此前只生成待审问题(zero_hit / seat_assist),答案靠人工补。本服务把
 * "候选答案"自动化:模型基于问题生成结构化 JSON(answer/citations/confidence),
 * confidence 低于阈值不落库;落地写 source=ai_generated、status=PENDING、audit_note
 * 存引用与置信度,人工审核通过才生效。任何失败都 fail-safe 跳过单条,不影响批量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsFaqGenerateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 低于此置信度的候选不生成——宁可留空让人工补,也不塞疑似胡编的答案。 */
    private static final double MIN_CONFIDENCE = 0.6;

    private final CsKnowledgeAnswerMapper answerMapper;
    private final CsChannelMapper channelMapper;
    private final CsChatFacade chatFacade;
    private final CsModuleProperties properties;

    /** 生成结果统计:候选数 / 生成数 / 低分跳过数 / 估算成本。 */
    public record GenerateResult(int candidates, int generated, int skipped, BigDecimal estimatedCost) {
    }

    /** 本空间任一启用渠道的 agentId 作为生成模型来源;没有渠道返回 null。 */
    private Long resolveAgentId(Long workspaceId) {
        List<CsChannel> channels = channelMapper.selectList(new LambdaQueryWrapper<CsChannel>()
            .eq(CsChannel::getWorkspaceId, workspaceId)
            .eq(CsChannel::getStatus, "0"));
        for (CsChannel c : channels) {
            if (c.getAgentId() != null) {
                return c.getAgentId();
            }
        }
        return null;
    }

    /** 对待审且答案为空的问题批量生成候选。limit 限制单次处理量,避免长事务。 */
    public GenerateResult generate(Long workspaceId, int limit) {
        int cap = Math.max(1, Math.min(50, limit));
        Long agentId = resolveAgentId(workspaceId);
        if (agentId == null) {
            throw new ServiceException("该空间未配置启用渠道或渠道未绑定 Agent,无法生成候选答案");
        }
        List<CsKnowledgeAnswer> pending = answerMapper.selectList(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getStatus, "PENDING")
            .and(w -> w.isNull(CsKnowledgeAnswer::getAnswer).or().eq(CsKnowledgeAnswer::getAnswer, ""))
            .orderByDesc(CsKnowledgeAnswer::getCreateTime)
            .last("limit " + cap));
        int generated = 0;
        int skipped = 0;
        long tokens = 0;
        for (CsKnowledgeAnswer one : pending) {
            String json = chatFacade.draftAnswer(agentId, one.getQuestion(), null);
            if (json == null) {
                skipped++;
                continue;
            }
            try {
                Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
                String answer = parsed.get("answer") == null ? null : String.valueOf(parsed.get("answer")).trim();
                double confidence = parsed.get("confidence") == null ? 0 : Double.parseDouble(String.valueOf(parsed.get("confidence")));
                if (answer == null || answer.isEmpty() || answer.length() > 4000 || confidence < MIN_CONFIDENCE) {
                    skipped++;
                    continue;
                }
                Object citationsValue = parsed.get("citations");
                String citationsJson;
                try {
                    citationsJson = citationsValue == null ? "[]" : MAPPER.writeValueAsString(citationsValue);
                } catch (Exception e) {
                    citationsJson = "[]";
                }
                if (citationsJson.length() > 2000) {
                    citationsJson = citationsJson.substring(0, 2000);
                }
                one.setAnswer(answer);
                one.setSource("ai_generated");
                one.setStatus("PENDING");
                one.setCitations(citationsJson);
                one.setConfidence(java.math.BigDecimal.valueOf(confidence));
                answerMapper.updateById(one);
                generated++;
                // 粗估 token:问题 + 生成答案,按 2 字符/token 估
                tokens += (long) (one.getQuestion().length() + answer.length()) / 2;
            } catch (Exception e) {
                log.warn("[CS] B3 FAQ 候选解析失败 id={}: {}", one.getId(), e.getMessage());
                skipped++;
            }
        }
        BigDecimal cost = CsCostService.costOf(tokens, properties.getCostPer1kTokens());
        return new GenerateResult(pending.size(), generated, skipped, cost);
    }

    /** 成本估算:不实际生成,按待审问题数与平均长度粗估 token 与费用。 */
    public GenerateResult estimate(Long workspaceId, int limit) {
        int cap = Math.max(1, Math.min(50, limit));
        Long count = answerMapper.selectCount(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getStatus, "PENDING")
            .and(w -> w.isNull(CsKnowledgeAnswer::getAnswer).or().eq(CsKnowledgeAnswer::getAnswer, "")));
        long tokens = (long) Math.min(count, cap) * 200L;
        return new GenerateResult((int) Math.min(count, cap), 0, 0,
            CsCostService.costOf(tokens, properties.getCostPer1kTokens()));
    }
}
