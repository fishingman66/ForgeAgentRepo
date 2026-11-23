package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.domain.CsConversation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A-2:情绪口径统一单测。锁三件事:① 有 SPI 信号时按分值+连击映射中文展示;
 * ② 无信号回退原词表(老会话/存量摘要零变更);③ 边界分值 -0.2/0.2 归属正确。
 */
@Tag("dev")
class CsEmotionUnificationTest {

    private static CsConversation conv(String score, Integer streak) {
        CsConversation c = new CsConversation();
        c.setLastSentimentScore(score == null ? null : new BigDecimal(score));
        c.setSentimentStreak(streak);
        return c;
    }

    @Test
    void negativeWithStreakIsAnger() {
        assertEquals("愤怒", CsHandoffSummaryService.emotionFromSignals(conv("-0.6", 3), List.of()));
        assertEquals("不满", CsHandoffSummaryService.emotionFromSignals(conv("-0.6", 1), List.of()));
    }

    @Test
    void positiveAndNeutralMapCorrectly() {
        assertEquals("满意", CsHandoffSummaryService.emotionFromSignals(conv("0.3", 0), List.of()));
        assertEquals("平静", CsHandoffSummaryService.emotionFromSignals(conv("0.0", 0), List.of()));
        // 边界:-0.2 恰在阈值上算负面,0.2 算正面
        assertEquals("不满", CsHandoffSummaryService.emotionFromSignals(conv("-0.2", 1), List.of()));
        assertEquals("满意", CsHandoffSummaryService.emotionFromSignals(conv("0.2", 0), List.of()));
    }

    @Test
    void noSignalFallsBackToLegacyLexicon() {
        assertEquals("未知", CsHandoffSummaryService.emotionFromSignals(conv(null, null), List.of()));
        assertEquals("平静", CsHandoffSummaryService.emotionFromSignals(conv(null, null), List.of("今天一切正常")));
        assertEquals("不满", CsHandoffSummaryService.emotionFromSignals(conv(null, null), List.of("太差劲了")));
        assertEquals("愤怒", CsHandoffSummaryService.emotionFromSignals(conv(null, null),
            List.of("太差劲了", "我要投诉")));
    }
}
