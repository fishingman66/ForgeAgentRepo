package com.forgeagent.callcenter.route;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsRouteRule;
import com.forgeagent.callcenter.mapper.CsRouteRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C5 规则引擎三道防护 + 优先级:
 * 量词防护(截断 + 命中跨度)、词组白名单、坏模式隔离。
 */
@Tag("dev")
class RouteRuleEngineTest {

    private CallcenterProperties properties;
    private RouteRuleEngine engine;
    private CsRouteRuleMapper mapper;

    @BeforeEach
    void setUp() {
        properties = new CallcenterProperties();
        properties.setRouteRuleCacheTtlMs(60_000L);
        properties.setRouteRuleMaxTextChars(200);
        properties.setRouteRuleDefaultMaxMatchLength(40);
    }

    // ------------------------------------------------------------------
    // 优先级
    // ------------------------------------------------------------------

    @Test
    void priorityOrderDecidesFirstMatch() {
        engine = engineWith(List.of(
            rule("refund_strong", 10, "REGEX", "退货", "refund", "TRANSFER", "after_sales"),
            rule("refund_weak", 20, "REGEX", "退", "refund_generic", "MARK", null)));

        RouteRuleEngine.Evaluation evaluation =
            engine.evaluate(1L, "我要退货退款", List.of());

        assertNotNull(evaluation.regexHit());
        assertEquals("refund_strong", evaluation.regexHit().ruleCode(), "小 priority 优先");
        assertEquals("after_sales", evaluation.regexHit().actionArg());
    }

    @Test
    void equalPriorityFallsBackToFirstLoaded() {
        engine = engineWith(List.of(
            rule("a", 10, "REGEX", "退款", "intent_a", "MARK", null),
            rule("b", 10, "REGEX", "退款", "intent_b", "MARK", null)));

        assertEquals("intent_a", engine.evaluate(1L, "退款", List.of()).regexHit().intentCode());
    }

    // ------------------------------------------------------------------
    // 量词防护
    // ------------------------------------------------------------------

    @Test
    void greedyQuantifierMatchBeyondCapIsRejected() {
        CsRouteRule rule = rule("span", 10, "REGEX", "退.{0,80}货", "refund", "MARK", null);
        rule.setMaxMatchLength(10);
        engine = engineWith(List.of(rule));

        String longSpan = "退" + "字".repeat(30) + "货";
        assertNull(engine.evaluate(1L, longSpan, List.of()).regexHit(),
            "命中片段 32 字超过上限 10,视为过匹配丢弃");

        assertNotNull(engine.evaluate(1L, "退货", List.of()).regexHit(), "短命中正常通过");
    }

    @Test
    void textIsTruncatedBeforeMatching() {
        properties.setRouteRuleMaxTextChars(10);
        engine = engineWith(List.of(rule("refund", 10, "REGEX", "退款", "refund", "MARK", null)));

        // 关键词落在截断点之后,不应命中
        assertNull(engine.evaluate(1L, "一二三四五六七八九十退款", List.of()).regexHit());
        assertNotNull(engine.evaluate(1L, "退款", List.of()).regexHit());
    }

    @Test
    void normalizeCollapsesWhitespace() {
        engine = engineWith(List.of());
        assertEquals("我要 退货", engine.normalize("  我要\n\t退货  "));
    }

    // ------------------------------------------------------------------
    // 词组白名单
    // ------------------------------------------------------------------

    @Test
    void phraseWhitelistRequiresAtLeastOnePhrase() {
        CsRouteRule rule = rule("refund", 10, "REGEX", "退", "refund", "MARK", null);
        rule.setPhraseWhitelist("退货,退款");
        engine = engineWith(List.of(rule));

        assertNull(engine.evaluate(1L, "我退休了想问问", List.of()).regexHit(),
            "白名单词组一个都没出现,不命中(挡住「退休」这类过匹配)");
        assertNotNull(engine.evaluate(1L, "我要退货", List.of()).regexHit(),
            "命中白名单任一词组即放行");
        assertNotNull(engine.evaluate(1L, "帮我退款", List.of()).regexHit());
    }

    @Test
    void blankWhitelistMeansNoConstraint() {
        engine = engineWith(List.of(rule("refund", 10, "REGEX", "退货", "refund", "MARK", null)));
        assertNotNull(engine.evaluate(1L, "退货", List.of()).regexHit());
        assertTrue(RouteRuleEngine.whitelistSatisfied("  ", "任意文本"), "空白白名单视为无约束");
        assertFalse(RouteRuleEngine.whitelistSatisfied("退货,退款", "我要退订"),
            "白名单外的表述一律不放行");
    }

    // ------------------------------------------------------------------
    // 坏模式隔离
    // ------------------------------------------------------------------

    @Test
    void invalidPatternIsIsolated() {
        engine = engineWith(List.of(
            rule("broken", 5, "REGEX", "[unclosed", "broken", "MARK", null),
            rule("refund", 10, "REGEX", "退货", "refund", "MARK", null)));

        RouteRuleEngine.Evaluation evaluation = engine.evaluate(1L, "我要退货", List.of());
        assertNotNull(evaluation.regexHit(), "坏模式只跳过自己,不影响后续规则");
        assertEquals("refund", evaluation.regexHit().intentCode());
    }

    @Test
    void catastrophicPatternIsSkipped() {
        engine = engineWith(List.of(rule("redos", 10, "REGEX", "(.*)+退货", "refund", "MARK", null)));
        assertNull(engine.evaluate(1L, "退货", List.of()).regexHit(), "嵌套量词按灾难回溯直接跳过");
    }

    // ------------------------------------------------------------------
    // LLM_ONLY 与加载
    // ------------------------------------------------------------------

    @Test
    void llmOnlyRulesAreCandidatesNotMatches() {
        engine = engineWith(List.of(
            rule("complaint_llm", 5, "LLM_ONLY", null, "complaint", "MARK", null),
            rule("refund", 20, "REGEX", "退货", "refund", "TRANSFER", "after_sales")));

        RouteRuleEngine.Evaluation evaluation = engine.evaluate(1L, "我要退货", List.of());
        assertEquals(1, evaluation.llmCandidates().size(), "高优先级 LLM 规则作为候选上抛");
        assertEquals("complaint", evaluation.llmCandidates().get(0).intentCode());
        assertEquals("refund", evaluation.regexHit().intentCode());
        assertTrue(evaluation.hasLlmCandidate());
    }

    @Test
    void onlyLlmRulesYieldNoRegexHit() {
        engine = engineWith(List.of(rule("c", 5, "LLM_ONLY", null, "complaint", "MARK", null)));
        RouteRuleEngine.Evaluation evaluation = engine.evaluate(1L, "你们太过分了", List.of());
        assertNull(evaluation.regexHit());
        assertTrue(evaluation.hasLlmCandidate());
    }

    @Test
    void disabledRulesAreFilteredEvenIfMapperReturnsThem() {
        CsRouteRule disabled = rule("off", 10, "REGEX", "退货", "refund", "MARK", null);
        disabled.setEnabled("1");
        engine = engineWith(List.of(disabled, rule("on", 20, "REGEX", "退款", "refund2", "MARK", null)));

        RouteRuleEngine.Evaluation evaluation = engine.evaluate(1L, "退货退款", List.of());
        assertEquals("refund2", evaluation.regexHit().intentCode(), "停用规则不参与匹配");
    }

    @Test
    void rulesAreCachedWithinTtl() {
        engine = engineWith(List.of(rule("refund", 10, "REGEX", "退货", "refund", "MARK", null)));
        engine.evaluate(1L, "退货", List.of());
        engine.evaluate(1L, "还是要退货", List.of());
        verify(mapper, times(1)).selectList(any(Wrapper.class));
    }

    @Test
    void loadFailureDegradesToNoRules() {
        mapper = mock(CsRouteRuleMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new IllegalStateException("db down"));
        engine = new RouteRuleEngine(properties, provider(mapper));

        RouteRuleEngine.Evaluation evaluation = engine.evaluate(1L, "我要退货", List.of());
        assertNull(evaluation.regexHit());
        assertFalse(evaluation.hasLlmCandidate(), "规则表不可用时按无规则处理,不抛给通话链路");
    }

    @Test
    void blankTextYieldsEmptyEvaluation() {
        engine = engineWith(List.of(rule("refund", 10, "REGEX", "退货", "refund", "MARK", null)));
        assertNull(engine.evaluate(1L, "   ", List.of()).regexHit());
        assertNull(engine.evaluate(1L, null, List.of()).regexHit());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private RouteRuleEngine engineWith(List<CsRouteRule> rules) {
        mapper = mock(CsRouteRuleMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(rules);
        return new RouteRuleEngine(properties, provider(mapper));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    static CsRouteRule rule(String code, int priority, String type, String pattern,
                            String intent, String action, String actionArg) {
        CsRouteRule rule = new CsRouteRule();
        rule.setWorkspaceId(1L);
        rule.setRuleCode(code);
        rule.setPriority(priority);
        rule.setMatchType(type);
        rule.setPattern(pattern);
        rule.setIntentCode(intent);
        rule.setAction(action);
        rule.setActionArg(actionArg);
        rule.setMaxMatchLength(40);
        rule.setEnabled("0");
        rule.setDelFlag(0L);
        return rule;
    }
}
