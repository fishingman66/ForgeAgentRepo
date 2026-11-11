package com.forgeagent.customerservice.service;

import java.util.List;

/**
 * 转人工关键词判定(P0,差距分析 4.1)。
 *
 * <p>客户明确说「转人工」时不该再跑一遍 FAQ 匹配与向量检索——那是几百毫秒到数秒的浪费，
 * 且很可能被兜底文案盖住。这里在消息入口前置判定，命中直接建单。</p>
 *
 * <p>关键词按渠道可配(渠道字段优先、平台默认兜底)，分隔符兼容中英文逗号/分号/顿号，
 * 与意图标签同一套拆分口径，运营不用记两套写法。</p>
 */
public final class CsHandoffKeywords {

    /** 平台默认关键词:覆盖「转人工 / 人工客服 / 找人工 / 转客服」等常见说法。 */
    public static final String DEFAULT_KEYWORDS = "转人工,人工客服,人工服务,真人客服,找人工,转客服";

    private CsHandoffKeywords() {
    }

    /** 命中判定:渠道配置为空时回落到平台默认。 */
    public static boolean matches(String text, String keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> words = resolve(keywords);
        String lower = text.toLowerCase();
        for (String word : words) {
            if (lower.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 生效词表:显式配置优先,空则用默认。 */
    public static List<String> resolve(String keywords) {
        List<String> words = CsIntentService.splitKeywords(keywords);
        return words.isEmpty() ? CsIntentService.splitKeywords(DEFAULT_KEYWORDS) : words;
    }
}
