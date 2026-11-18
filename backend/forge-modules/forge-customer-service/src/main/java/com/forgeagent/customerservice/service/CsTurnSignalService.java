package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.callcenter.common.sentiment.SentimentAnalyzer;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTurnSignals;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * B1:每轮对话信号计算(docs/11-B1)——入口一次计算,写入会话供规则引擎与指标消费。
 *
 * <p>情绪分析复用上移到 callcenter-common 的 {@link SentimentAnalyzer} SPI(词表版,
 * 与语音洞察同一口径同一套词表),不在客服域另造引擎;分析器缺失时降级为
 * NEUTRAL/0,绝不阻断主链路。重复进线按 (visitor_id, create_time) 24h 窗口统计,
 * 未含意图维度——意图是本轮刚算出的,历史会话的 last_intent_code 随时间回填,
 * 意图维度的精确重复判定留给后续版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsTurnSignalService {

    /** 重复进线统计窗口。 */
    static final long REPEAT_WINDOW_MS = 24L * 3600 * 1000;

    private final CsIntentService intentService;
    private final ObjectProvider<SentimentAnalyzer> sentimentAnalyzerProvider;
    private final CsConversationMapper conversationMapper;
    private final AgentSessionMapper sessionMapper;

    /** 计算本轮信号并回填会话的信号字段(意图/情绪连击/情绪分),随既有 updateById 落库。 */
    public CsTurnSignals compute(Long workspaceId, CsConversation conv, String content) {
        CsIntentService.Match match = safeClassify(workspaceId, content);

        String label = SentimentAnalyzer.Labels.NEUTRAL;
        double score = 0;
        SentimentAnalyzer analyzer = sentimentAnalyzerProvider.getIfAvailable();
        if (analyzer != null && content != null && !content.isBlank()) {
            try {
                SentimentAnalyzer.Result result = analyzer.analyze(content);
                label = result.label();
                score = result.score();
            } catch (Exception e) {
                log.warn("[CS] 情绪分析失败 conversation={}: {}", conv.getId(), e.getMessage());
            }
        }

        Date since = new Date(System.currentTimeMillis() - REPEAT_WINDOW_MS);
        Long repeatTotal = conversationMapper.selectCount(new LambdaQueryWrapper<CsConversation>()
            .eq(CsConversation::getVisitorId, conv.getVisitorId())
            .ne(CsConversation::getId, conv.getId())
            .ge(CsConversation::getCreateTime, since));
        int repeatTotal24h = repeatTotal == null ? 0 : repeatTotal.intValue();

        int turnCount = 0;
        if (conv.getAgentSessionId() != null) {
            var session = sessionMapper.selectById(conv.getAgentSessionId());
            if (session != null && session.getMessageCount() != null) {
                turnCount = session.getMessageCount() / 2;
            }
        }

        int previousStreak = conv.getSentimentStreak() == null ? 0 : conv.getSentimentStreak();
        int streak = nextStreak(previousStreak, score);

        conv.setLastIntentCode(match == null ? null : match.code());
        conv.setSentimentStreak(streak);
        conv.setLastSentimentScore(java.math.BigDecimal.valueOf(score));
        return new CsTurnSignals(match == null ? null : match.code(), match == null ? null : match.name(),
            label, score, repeatTotal24h, turnCount, streak);
    }

    /** 意图分类失败不阻断主链路(信号是增益,不是闸门)。 */
    private CsIntentService.Match safeClassify(Long workspaceId, String content) {
        try {
            return content == null || content.isBlank() ? null
                : intentService.classify(workspaceId, content, java.util.List.of());
        } catch (Exception e) {
            log.warn("[CS] 意图分类失败 workspaceId={}: {}", workspaceId, e.getMessage());
            return null;
        }
    }

    /**
     * 负面连击口径:得分不超阈值(≤ threshold,含等于)时连击 +1,否则清零。
     * 与语音洞察负面阈值语义一致(阈值同为负值区间判定)。
     */
    public static int nextStreak(int previousStreak, double score, double threshold) {
        return score <= threshold ? previousStreak + 1 : 0;
    }

    private static int nextStreak(int previousStreak, double score) {
        return nextStreak(previousStreak, score, CsHandoffRuleEngine.DEFAULT_SENTIMENT_THRESHOLD);
    }
}
