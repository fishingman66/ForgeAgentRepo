package com.forgeagent.agentcore.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 临时探针(验证完即删):调用框架 formatter 的真实转换路径。
 * 构造成功不代表运行期链接成立——formatter 会调更深层的 SDK 类型,
 * 若新版 SDK 删了这些类/方法,这里必然抛 NoClassDefFoundError / NoSuchMethodError。
 */
@Tag("dev")
class TmpRuntimeLinkProbeTest {

    private static Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).textContent("hello").build();
    }

    @Test
    void anthropicFormatterRealPath() {
        AnthropicChatModel model = AnthropicChatModel.builder()
            .apiKey("sk-fake")
            .modelName("claude-sonnet-4")
            .baseUrl("https://gw.example.com/v1")
            .stream(false)
            .build();
        List<?> out = new AnthropicChatFormatter().doFormat(List.of(userMsg()));
        assertEquals(1, out.size());
    }

    @Test
    void geminiFormatterRealPath() throws Exception {
        GeminiChatModel model = GeminiChatModel.builder()
            .apiKey("fake")
            .modelName("gemini-2.5-flash")
            .baseUrl("https://gw.example.com/v1")
            .streamEnabled(false)
            .build();
        // GeminiChatFormatter.doFormat 为 protected,反射调用同一方法体
        GeminiChatFormatter fmt = new GeminiChatFormatter();
        var mtd = GeminiChatFormatter.class.getDeclaredMethod("doFormat", List.class);
        mtd.setAccessible(true);
        List<?> out = (List<?>) mtd.invoke(fmt, List.of(userMsg()));
        assertEquals(1, out.size());
    }
}
