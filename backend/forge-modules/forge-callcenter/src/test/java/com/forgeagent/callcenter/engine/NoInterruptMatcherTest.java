package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsVoiceNoInterrupt;
import com.forgeagent.callcenter.mapper.CsVoiceNoInterruptMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 不允许打断词组单测(C2):匹配器精确/模糊语义 + 服务降级与 DB 装配。
 */
@Tag("dev")
class NoInterruptMatcherTest {

    @Test
    void exactMatchSkipsBargeIn() {
        NoInterruptMatcher matcher = new NoInterruptMatcher(List.of("嗯", "好的"), List.of());
        assertTrue(matcher.shouldSkip("嗯"));
        assertTrue(matcher.shouldSkip(" 好的 "), "trim + 大小写无关");
        assertTrue(matcher.shouldSkip("嗯"));
        assertFalse(matcher.shouldSkip("嗯嗯我要退货"), "精确模式不含子串");
        assertFalse(matcher.shouldSkip("我要退货"));
        assertFalse(matcher.shouldSkip(""));
        assertFalse(matcher.shouldSkip(null));
        assertFalse(matcher.shouldSkip("   "), "纯空白不豁免");
    }

    @Test
    void fuzzyMatchContainsWord() {
        NoInterruptMatcher matcher = new NoInterruptMatcher(List.of(), List.of("不好意思"));
        assertTrue(matcher.shouldSkip("不好意思我想问下"));
        assertFalse(matcher.shouldSkip("我要退货"));
    }

    @Test
    void serviceFallsBackToPropertiesWhenMapperMissing() {
        CallcenterProperties properties = new CallcenterProperties();
        properties.setNoInterruptWords(List.of("嗯", "好的"));
        @SuppressWarnings("unchecked")
        ObjectProvider<CsVoiceNoInterruptMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        NoInterruptService service = new NoInterruptService(properties, provider);
        NoInterruptMatcher matcher = service.matcher();
        assertTrue(matcher.shouldSkip("嗯"));
        assertEquals(List.of("嗯", "好的"), matcher.exactWords());
    }

    @Test
    void serviceMergesDbRowsIntoMatcher() {
        CallcenterProperties properties = new CallcenterProperties();
        properties.setNoInterruptWords(List.of("嗯"));

        CsVoiceNoInterrupt row = new CsVoiceNoInterrupt();
        row.setWord("不好意思");
        row.setMatchMode("fuzzy");
        row.setEnabled("1");
        row.setDelFlag(0L);
        CsVoiceNoInterruptMapper mapper = mock(CsVoiceNoInterruptMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        @SuppressWarnings("unchecked")
        ObjectProvider<CsVoiceNoInterruptMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);

        NoInterruptService service = new NoInterruptService(properties, provider);
        NoInterruptMatcher matcher = service.matcher();
        assertTrue(matcher.shouldSkip("嗯"));
        assertTrue(matcher.shouldSkip("不好意思请问"));
        assertEquals(List.of("嗯"), matcher.exactWords());
        assertEquals(List.of("不好意思"), matcher.fuzzyWords());
    }
}
