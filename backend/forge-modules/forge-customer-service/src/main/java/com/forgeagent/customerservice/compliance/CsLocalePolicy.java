package com.forgeagent.customerservice.compliance;

import com.forgeagent.customerservice.domain.CsChannel;
import org.springframework.stereotype.Component;

/**
 * 多语言策略(C4):按渠道默认语种 + 轻量文本特征选择兜底话术。
 *
 * <p>不做在线翻译与语种模型调用;只解决「英文访客收到中文兜底」这类明显错配。</p>
 */
@Component
public class CsLocalePolicy {

    public String resolveFallback(CsChannel channel, String text) {
        String locale = detect(text, channel.getLocale());
        if (locale != null && locale.toLowerCase().startsWith("en")
            && channel.getFallbackReplyEn() != null && !channel.getFallbackReplyEn().isBlank()) {
            return channel.getFallbackReplyEn();
        }
        return channel.getFallbackReply();
    }

    /** 中文字符占比超过 20% 视为中文;纯 ASCII 字母视为英文;其余沿用渠道默认。 */
    public String detect(String text, String defaultLocale) {
        if (text == null || text.isBlank()) {
            return defaultLocale == null ? "zh-CN" : defaultLocale;
        }
        long chinese = text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        if (chinese * 5 >= text.length()) {
            return "zh-CN";
        }
        if (text.matches("[\\x20-\\x7E]+")) {
            return "en-US";
        }
        return defaultLocale == null ? "zh-CN" : defaultLocale;
    }
}
