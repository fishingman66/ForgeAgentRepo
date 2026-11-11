package com.forgeagent.agentcore.service;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import com.forgeagent.common.core.utils.StringUtils;

/**
 * AgentScope 模型卡工厂:按供应商路由协议(DashScope 原生 / OpenAI 兼容)
 *
 * <p>供聊天执行器与工作流试运行共用,统一模型接入参数到 AgentScope Model 实例的映射。</p>
 *
 * @author ForgeAgent
 */
public final class AgentScopeModelFactory {

    private AgentScopeModelFactory() {
    }

    /**
     * 构建模型实例
     *
     * @param baseUrl     模型 API 基础地址
     * @param apiKey      模型 API 密钥
     * @param modelName   模型标识
     * @param temperature 采样温度(可空)
     * @param maxTokens   最大输出 tokens(可空)
     */
    public static Model build(String baseUrl, String apiKey, String modelName,
                              Double temperature, Integer maxTokens) {
        GenerateOptions options = GenerateOptions.builder()
            .temperature(temperature)
            .maxTokens(maxTokens)
            .build();
        // DashScope 原生端点(非 compatible-mode)走 DashScope 专有协议
        if (baseUrl.contains("dashscope.aliyuncs.com") && !baseUrl.contains("compatible-mode")) {
            return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(false)
                .defaultOptions(options)
                // 与 1.x 行为对齐:统一走 generate_response 合成工具的结构化输出路径
                .nativeStructuredOutput(false)
                .build();
        }
        return OpenAIChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .baseUrl(normalizeBaseUrl(baseUrl))
            .stream(false)
            .generateOptions(options)
            .nativeStructuredOutput(false)
            .build();
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
