package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0:转人工关键词判定(渠道配置优先 / 默认兜底 / 分隔符兼容)。 */
@Tag("dev")
class CsHandoffKeywordsTest {

    @Test
    void defaultKeywordsCatchCommonPhrases() {
        assertTrue(CsHandoffKeywords.matches("我要转人工", null));
        assertTrue(CsHandoffKeywords.matches("请帮我找人工客服", ""));
        assertTrue(CsHandoffKeywords.matches("转客服吧", "   "));
    }

    @Test
    void channelKeywordsOverrideDefault() {
        // 渠道自定义词表里没有「转人工」时,不应用默认兜底
        assertFalse(CsHandoffKeywords.matches("我要转人工", "找经理,投诉"));
        assertTrue(CsHandoffKeywords.matches("帮我找经理", "找经理,投诉"));
    }

    @Test
    void separatorsAreCompatible() {
        assertEquals(3, CsHandoffKeywords.resolve("转人工，人工客服、找人工").size());
        assertTrue(CsHandoffKeywords.matches("人工客服在吗", "转人工；人工客服"));
    }

    @Test
    void blankInputNeverMatches() {
        assertFalse(CsHandoffKeywords.matches(null, null));
        assertFalse(CsHandoffKeywords.matches("   ", "转人工"));
    }

    @Test
    void partialWordDoesNotTrigger() {
        // 「人工」单词不应命中「转人工」这类长词,避免误杀正常问句
        assertFalse(CsHandoffKeywords.matches("这个产品是人工制造的吗", "转人工,找人工"));
    }
}
