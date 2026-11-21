package com.forgeagent.agentcore.service;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * C3-3:模型工厂协议路由构造级单测(已获准以假 apiKey 替代真 Key 回归)。
 * builder 构造不触发网络,断言路由分支产物类型正确;端到端连通性需真实 Key,不在单测范围。
 * 锁五件事:① 显式 provider=anthropic/gemini 走原生协议并真实构造出 Model(官方 SDK 已引入);
 * ② provider=dashscope 显式路由;
 * ③ provider 空/openai 时回退既有 URL 特征路由(dashscope 端点→DashScope,其余→OpenAI);
 * ④ 大小写不敏感;⑤ normalizeBaseUrl 行为不变。
 */
@Tag("dev")
class AgentScopeModelFactoryProtocolTest {

    private static final String FAKE_KEY = "sk-test-not-real";

    @Test
    void anthropicProviderRoutesToNativeProtocol() {
        // SDK 已显式引入(com.anthropic:anthropic-java),构造期即可拿到真实 AnthropicChatModel
        Model model = AgentScopeModelFactory.build(
            "anthropic", "https://gw.example.com/v1", FAKE_KEY, "claude-sonnet-4", 0.7, 4096);
        assertInstanceOf(AnthropicChatModel.class, model);
    }

    @Test
    void geminiProviderRoutesToNativeProtocol() {
        // SDK 已显式引入(com.google.genai:google-genai);provider 大小写不敏感
        Model model = AgentScopeModelFactory.build(
            "Gemini", "https://gw.example.com/v1", FAKE_KEY, "gemini-2.5-flash", null, null);
        assertInstanceOf(GeminiChatModel.class, model);
    }

    @Test
    void explicitDashScopeProviderRoutesToNativeProtocol() {
        Model model = AgentScopeModelFactory.build(
            "DASHSCOPE", "https://gw.example.com/v1", FAKE_KEY, "qwen-max", null, null);
        assertInstanceOf(DashScopeChatModel.class, model);
    }

    @Test
    void blankProviderFallsBackToUrlHeuristics() {
        // 既有口径:官方 dashscope 原生端点(非 compatible-mode)走 DashScope
        Model dashModel = AgentScopeModelFactory.build(
            null, "https://dashscope.aliyuncs.com/api/v2", FAKE_KEY, "qwen-max", null, null);
        assertInstanceOf(DashScopeChatModel.class, dashModel);
        // compatible-mode 与普通域走 OpenAI 兼容
        Model compat = AgentScopeModelFactory.build(
            "", "https://dashscope.aliyuncs.com/compatible-mode/v1", FAKE_KEY, "qwen-max", null, null);
        assertInstanceOf(OpenAIChatModel.class, compat);
        Model plain = AgentScopeModelFactory.build(
            null, "https://api.example.com/v1", FAKE_KEY, "gpt-x", null, null);
        assertInstanceOf(OpenAIChatModel.class, plain);
    }

    @Test
    void openaiProviderExplicitKeepsOpenAIProtocol() {
        Model model = AgentScopeModelFactory.build(
            "openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", FAKE_KEY, "qwen-max", null, null);
        assertInstanceOf(OpenAIChatModel.class, model);
    }

    @Test
    void legacyFiveArgOverloadUnchanged() {
        Model dashModel = AgentScopeModelFactory.build(
            "https://dashscope.aliyuncs.com/api/v2", FAKE_KEY, "qwen-max", null, null);
        assertInstanceOf(DashScopeChatModel.class, dashModel, "旧 5 参重载行为必须保持不变");
        Model plain = AgentScopeModelFactory.build(
            "https://api.example.com/v1", FAKE_KEY, "gpt-x", null, null);
        assertInstanceOf(OpenAIChatModel.class, plain);
    }

    @Test
    void normalizeBaseUrlStripsChatCompletionsSuffix() {
        assertEquals("https://api.example.com/v1",
            AgentScopeModelFactory.normalizeBaseUrl("https://api.example.com/v1/chat/completions/"));
        assertEquals("https://api.example.com/v1",
            AgentScopeModelFactory.normalizeBaseUrl("https://api.example.com/v1"));
    }

    @Test
    void blankBaseUrlPassesThrough() {
        // 实测 stripEnd 会连空白一起剥:空白串归一化为空串,不抛异常(非空校验由调用方负责)
        assertEquals("", AgentScopeModelFactory.normalizeBaseUrl("  "));
    }
}
