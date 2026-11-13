package com.forgeagent.callcenter.route;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.engine.ivr.IntentionClassifier;
import com.forgeagent.callcenter.engine.ivr.RegexIntentionClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C5 双引擎裁决顺序:规则命中优先、LLM 只能采纳规则表声明过的意图码、
 * 无实现/未开关/调用失败一律降级到正则库。
 */
@Tag("dev")
class HybridIntentionClassifierTest {

    private RouteRuleEngine engine;
    private RegexIntentionClassifier fallback;
    private LlmIntentClassifier llm;
    private CallcenterProperties properties;

    @BeforeEach
    void setUp() {
        engine = mock(RouteRuleEngine.class);
        fallback = mock(RegexIntentionClassifier.class);
        llm = mock(LlmIntentClassifier.class);
        properties = new CallcenterProperties();
    }

    @Test
    void ruleHitWinsAndSkipsFallback() {
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(
            new RouteRuleEngine.Evaluation(
                new RouteRuleEngine.RouteMatch("refund", "TRANSFER", "after_sales",
                    "refund_strong", 10, "退货"), List.of()));
        HybridIntentionClassifier classifier = classifier(llm);

        HybridIntentionClassifier.Decision decision = classifier.route(1L, "我要退货", List.of());

        assertEquals("refund", decision.intentCode());
        assertEquals("TRANSFER", decision.action());
        assertEquals("RULE", decision.engine());
        assertEquals(1.0, decision.confidence(), 1e-9);
        verify(fallback, never()).classify(anyLong(), anyString(), anyList());
    }

    @Test
    void fallbackEngineUsedWhenNoRuleMatches() {
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(RouteRuleEngine.Evaluation.empty());
        when(fallback.classify(anyLong(), anyString(), anyList()))
            .thenReturn(new IntentionClassifier.Result("query_order", 1.0));
        HybridIntentionClassifier classifier = classifier(llm);

        HybridIntentionClassifier.Decision decision = classifier.route(1L, "查下订单", List.of());

        assertEquals("query_order", decision.intentCode());
        assertEquals("FALLBACK_REGEX", decision.engine());
        assertEquals("MARK", decision.action(), "兜底路径没有动作配置,只标记");
        assertEquals(0.8, decision.confidence(), 1e-9);
    }

    @Test
    void llmDecisionAcceptedOnlyWhenIntentDeclared() {
        properties.setRouteLlmEnabled(true);
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(
            new RouteRuleEngine.Evaluation(null, List.of(
                new RouteRuleEngine.LlmCandidate("complaint", "TRANSFER", "complaint_queue",
                    "complaint_llm", 5))));
        when(llm.available()).thenReturn(true);
        when(llm.classify(anyLong(), anyString(), anyList()))
            .thenReturn(new IntentionClassifier.Result("complaint", 0.77));
        HybridIntentionClassifier classifier = classifier(llm);

        HybridIntentionClassifier.Decision decision = classifier.route(1L, "你们太过分了", List.of());

        assertEquals("complaint", decision.intentCode());
        assertEquals("LLM", decision.engine());
        assertEquals("TRANSFER", decision.action(), "动作只来自规则表");
        assertEquals("complaint_queue", decision.actionArg());
        assertEquals(0.77, decision.confidence(), 1e-9);
    }

    @Test
    void llmCodeOutsideDeclaredRulesIsDiscarded() {
        properties.setRouteLlmEnabled(true);
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(
            new RouteRuleEngine.Evaluation(
                new RouteRuleEngine.RouteMatch("refund", "MARK", null, "refund", 20, "退货"),
                List.of(new RouteRuleEngine.LlmCandidate("complaint", "MARK", null, "complaint_llm", 5))));
        when(llm.available()).thenReturn(true);
        when(llm.classify(anyLong(), anyString(), anyList()))
            .thenReturn(new IntentionClassifier.Result("凭空发明的意图", 0.99));
        HybridIntentionClassifier classifier = classifier(llm);

        HybridIntentionClassifier.Decision decision = classifier.route(1L, "我要退货", List.of());

        assertEquals("refund", decision.intentCode(), "未声明的意图码不采纳,回落到规则命中");
        assertEquals("RULE", decision.engine());
    }

    @Test
    void llmSwitchOffKeepsRulePath() {
        properties.setRouteLlmEnabled(false);
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(
            new RouteRuleEngine.Evaluation(
                new RouteRuleEngine.RouteMatch("refund", "MARK", null, "refund", 20, "退货"),
                List.of(new RouteRuleEngine.LlmCandidate("complaint", "MARK", null, "c", 5))));
        HybridIntentionClassifier classifier = classifier(llm);

        assertEquals("RULE", classifier.route(1L, "我要退货", List.of()).engine());
        verify(llm, never()).classify(anyLong(), anyString(), anyList());
    }

    @Test
    void llmFailureDegradesWithoutThrowing() {
        properties.setRouteLlmEnabled(true);
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(
            new RouteRuleEngine.Evaluation(
                new RouteRuleEngine.RouteMatch("refund", "MARK", null, "refund", 20, "退货"),
                List.of(new RouteRuleEngine.LlmCandidate("complaint", "MARK", null, "c", 5))));
        when(llm.available()).thenReturn(true);
        when(llm.classify(any(), any(), any())).thenThrow(new IllegalStateException("模型超时"));
        HybridIntentionClassifier classifier = classifier(llm);

        HybridIntentionClassifier.Decision decision = classifier.route(1L, "我要退货", List.of());
        assertEquals("RULE", decision.engine(), "模型故障降级到规则,不抛给通话链路");
    }

    @Test
    void blankTextAndNoDecisionReturnNull() {
        when(engine.evaluate(anyLong(), anyString(), anyList())).thenReturn(RouteRuleEngine.Evaluation.empty());
        when(fallback.classify(anyLong(), anyString(), anyList())).thenReturn(null);
        HybridIntentionClassifier classifier = classifier(llm);

        assertNull(classifier.route(1L, "  ", List.of()));
        assertNull(classifier.route(1L, "随便说点什么", List.of()));
        assertNull(classifier.classify(1L, "随便说点什么", List.of()));
    }

    private HybridIntentionClassifier classifier(LlmIntentClassifier llmBean) {
        return new HybridIntentionClassifier(engine, fallback, provider(llmBean), properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
