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
 * SDK 与 agentscope 框架扩展类的二进制兼容性回归。
 *
 * <p>构造成功不代表运行期链接成立——formatter 的真实转换路径会调用更深层 SDK 类型。
 * 实测 anthropic-java 3.x 删除了 MessageParam$Builder/builder()/temperature 等,
 * 构造能过但这里会抛 NoSuchMethodError。保留本测试以锁住版本边界,
 * 未来升级 SDK 或框架时能第一时间发现不兼容。</p>
 */
@Tag("dev")
class AgentScopeSdkCompatibilityTest {

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
