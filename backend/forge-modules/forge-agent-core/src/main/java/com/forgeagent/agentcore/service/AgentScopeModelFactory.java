package com.forgeagent.agentcore.service;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import com.forgeagent.common.core.utils.StringUtils;

/**
 * AgentScope 模型卡工厂:按供应商路由协议(DashScope 原生 / OpenAI 兼容 / Anthropic 原生 / Gemini 原生)
 *
 * <p>供聊天执行器与工作流试运行共用,统一模型接入参数到 AgentScope Model 实例的映射。</p>
 *
 * <p>C3-3:路由优先级为<b>显式 provider 优先</b>(AgentModel.provider,模型卡可配
 * openai/anthropic/dashscope/ollama/custom),未配置时回退既有 baseUrl URL 特征判断
 * (dashscope 原生端点 vs 兼容模式)。显式 provider 让自定义网关(如 one-api 聚合域)
 * 也能走对的协议——URL 特征只认官方端点,网关域无从判断。</p>
 *
 * <p>验证口径(已获准):用假 apiKey 做构造级回归——builder 构造不触发网络,
 * 断言产物类型与路由分支正确性;端到端连通性才需要真实 Key。</p>
 *
 * <p>依赖边界:agentscope 2.0.5 的 all-in-one jar 已打入 Anthropic/Gemini 扩展类,但其官方 SDK
 * 未被传递引入,故 forge-agent-core 显式声明 com.anthropic:anthropic-java:2.52.0 与
 * com.google.genai:google-genai:1.45.0——取框架扩展模块编译时的同款版本(兼容优先于追新)。
 * build 内的 NoClassDefFoundError 守护仅作部署裁剪兜底,正常环境不会触发。</p>
 *
 * @author ForgeAgent
 */
public final class AgentScopeModelFactory {

    private AgentScopeModelFactory() {
    }

    /**
     * 构建模型实例(provider 未配置,按 baseUrl URL 特征路由——既有行为,保持不变)
     */
    public static Model build(String baseUrl, String apiKey, String modelName,
                              Double temperature, Integer maxTokens) {
        return build(null, baseUrl, apiKey, modelName, temperature, maxTokens);
    }

    /**
     * 构建模型实例(显式 provider 优先路由)
     *
     * @param provider    模型卡 provider(openai/anthropic/dashscope/gemini/ollama/custom;空按 URL 特征)
     * @param baseUrl     模型 API 基础地址
     * @param apiKey      模型 API 密钥
     * @param modelName   模型标识
     * @param temperature 采样温度(可空)
     * @param maxTokens   最大输出 tokens(可空)
     */
    public static Model build(String provider, String baseUrl, String apiKey, String modelName,
                              Double temperature, Integer maxTokens) {
        GenerateOptions options = GenerateOptions.builder()
            .temperature(temperature)
            .maxTokens(maxTokens)
            .build();
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String p = provider == null ? "" : provider.trim().toLowerCase();
        // C3-3:显式 provider 优先——anthropic / gemini 走原生协议,不依赖 baseUrl 特征。
        // SDK 已显式声明;此处守护仅防部署裁剪导致缺类,给出带坐标的业务异常而非裸 NoClassDefFoundError。
        if ("anthropic".equals(p)) {
            try {
                return AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .baseUrl(normalizedBaseUrl)
                    .stream(false)
                    .defaultOptions(options)
                    .build();
            } catch (NoClassDefFoundError e) {
                throw missingSdk("Anthropic", "com.anthropic:anthropic-java", e);
            }
        }
        if ("gemini".equals(p)) {
            // 框架 Gemini Builder 的流式开关是 streamEnabled(与 Anthropic/OpenAI 的 stream 不同名)
            try {
                return GeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .baseUrl(normalizedBaseUrl)
                    .streamEnabled(false)
                    .defaultOptions(options)
                    .build();
            } catch (NoClassDefFoundError e) {
                throw missingSdk("Gemini", "com.google.genai:google-genai", e);
            }
        }
        if ("dashscope".equals(p)) {
            return dashScope(apiKey, modelName, options);
        }
        // provider 未配置(openai/custom/ollama 等):回退既有 URL 特征路由,行为与历史一致
        if (baseUrl.contains("dashscope.aliyuncs.com") && !baseUrl.contains("compatible-mode")) {
            return dashScope(apiKey, modelName, options);
        }
        return OpenAIChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .baseUrl(normalizedBaseUrl)
            .stream(false)
            .generateOptions(options)
            .nativeStructuredOutput(false)
            .build();
    }

    private static Model dashScope(String apiKey, String modelName, GenerateOptions options) {
        return DashScopeChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .stream(false)
            .defaultOptions(options)
            // 与 1.x 行为对齐:统一走 generate_response 合成工具的结构化输出路径
            .nativeStructuredOutput(false)
            .build();
    }

    /** 原生协议官方 SDK 缺失时的统一报错(给出依赖坐标,运维按需引入) */
    private static com.forgeagent.common.core.exception.ServiceException missingSdk(
        String protocol, String coordinate, NoClassDefFoundError cause) {
        return new com.forgeagent.common.core.exception.ServiceException(
            protocol + " 原生协议需要引入官方 SDK 依赖(" + coordinate
                + "),当前运行环境未包含;也可改用 OpenAI 兼容网关接入。缺失类:"
                + cause.getMessage());
    }

    /**
     * 归一化平台存储的 baseUrl:去掉结尾的 /chat/completions,统一为 API 根地址
     */
    public static String normalizeBaseUrl(String baseUrl) {
        String url = StringUtils.stripEnd(baseUrl.trim(), "/");
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        return url;
    }
}
