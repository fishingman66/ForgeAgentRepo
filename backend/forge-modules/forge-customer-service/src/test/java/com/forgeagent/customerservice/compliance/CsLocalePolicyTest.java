package com.forgeagent.customerservice.compliance;

import com.forgeagent.customerservice.domain.CsChannel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** C4:多语种兜底选择。 */
@Tag("dev")
class CsLocalePolicyTest {

    private final CsLocalePolicy policy = new CsLocalePolicy();

    private CsChannel channel() {
        CsChannel channel = new CsChannel();
        channel.setFallbackReply("中文兜底");
        channel.setFallbackReplyEn("English fallback");
        channel.setLocale("zh-CN");
        return channel;
    }

    @Test
    void chineseTextUsesChineseFallback() {
        assertEquals("中文兜底", policy.resolveFallback(channel(), "我想问一下退货政策"));
    }

    @Test
    void englishTextUsesEnglishFallback() {
        assertEquals("English fallback", policy.resolveFallback(channel(), "How can I return this item?"));
    }

    @Test
    void blankTextUsesChannelDefault() {
        assertEquals("中文兜底", policy.resolveFallback(channel(), ""));
    }
}
