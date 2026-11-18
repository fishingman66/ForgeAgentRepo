package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.domain.CsTurnSignals;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2:转人工规则引擎——顺序求值 / 渠道 JSON 整体替代默认 / 坏 JSON 降级默认 /
 * enabled=false 跳过 / 内置默认口径(关键词+情绪阈值+重复进线开,轮次上限关)。
 */
@Tag("dev")
class CsHandoffRuleEngineTest {

    private static CsTurnSignals signals(int streak, int repeat, int turns) {
        return new CsTurnSignals(null, null, "NEGATIVE", -0.8, repeat, turns, streak);
    }

    @Test
    void defaultRulesFireOnKeywordSentimentAndRepeat() {
        // 关键词优先(内置顺序:KEYWORD → SENTIMENT → REPEAT → TURN_LIMIT)
        assertEquals("KEYWORD", CsHandoffRuleEngine.evaluate(
            "我要转人工", null, "转人工,人工客服", signals(0, 0, 0)).ruleType());
        assertEquals("SENTIMENT", CsHandoffRuleEngine.evaluate(
            "东西质量太差了", null, "转人工", signals(2, 0, 0)).ruleType());
        assertEquals("REPEAT", CsHandoffRuleEngine.evaluate(
            "还没解决吗", null, "转人工", signals(0, 2, 0)).ruleType());
    }

    @Test
    void defaultRulesStayQuietBelowThresholds() {
        assertFalse(CsHandoffRuleEngine.evaluate(
            "帮我查下物流", null, "转人工", signals(1, 0, 0)).hit());
        assertFalse(CsHandoffRuleEngine.evaluate(
            "帮我查下物流", null, "转人工", signals(0, 1, 0)).hit());
        // 轮次上限默认关闭:即使轮数很高也不触发
        assertFalse(CsHandoffRuleEngine.evaluate(
            "帮我查下物流", null, "转人工", signals(0, 0, 99)).hit());
    }

    @Test
    void channelJsonOverridesDefaultsEntirely() {
        // 显式配置:只开轮次上限(阈值 3 轮)——情绪/重复进线未写出即不启用
        String json = "[{\"type\":\"TURN_LIMIT\",\"enabled\":true,\"turnLimit\":3}]";
        assertTrue(CsHandoffRuleEngine.evaluate(
            "帮我查下物流", json, "转人工", signals(5, 5, 3)).hit());
        assertFalse(CsHandoffRuleEngine.evaluate(
            "帮我查下物流", json, "转人工", signals(5, 5, 2)).hit());
    }

    @Test
    void channelJsonCanDisableBuiltinRules() {
        String json = "[{\"type\":\"KEYWORD\",\"enabled\":true},"
            + "{\"type\":\"SENTIMENT\",\"enabled\":false},"
            + "{\"type\":\"REPEAT\",\"enabled\":false}]";
        assertFalse(CsHandoffRuleEngine.evaluate(
            "还没解决吗", json, "转人工", signals(5, 5, 0)).hit());
    }

    @Test
    void badJsonFallsBackToDefaults() {
        assertEquals("KEYWORD", CsHandoffRuleEngine.evaluate(
            "转人工", "{not-valid-json", "转人工", signals(0, 0, 0)).ruleType());
    }

    @Test
    void unknownTypesAreSkipped() {
        String json = "[{\"type\":\"MAGIC\",\"enabled\":true},"
            + "{\"type\":\"REPEAT\",\"enabled\":true,\"repeatLimit\":5}]";
        CsHandoffRuleEngine.HandoffDecision decision =
            CsHandoffRuleEngine.evaluate("还没解决", json, "转人工", signals(0, 3, 0));
        // MAGIC 被跳过,REPEAT 阈值 5 未到
        assertFalse(decision.hit());
        assertTrue(CsHandoffRuleEngine.evaluate("还没解决", json, "转人工", signals(0, 5, 0)).hit());
    }

    @Test
    void parseDefaultsMatchSpec() {
        List<CsHandoffRuleEngine.RuleSpec> rules = CsHandoffRuleEngine.parse(null);
        assertEquals(4, rules.size());
        assertEquals("KEYWORD", rules.get(0).type());
        assertEquals(CsHandoffRuleEngine.DEFAULT_SENTIMENT_STREAK, rules.get(1).streakLimit());
        assertEquals(CsHandoffRuleEngine.DEFAULT_REPEAT_LIMIT, rules.get(2).repeatLimit());
        assertFalse(rules.get(3).enabled());
    }
}
