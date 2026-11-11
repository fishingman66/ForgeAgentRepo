package com.forgeagent.agent.service.runner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.forgeagent.agent.config.AgentRuntimeProperties;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.AgentRunner.RunnerContext.ChatTurn;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容执行器:真实调用 Chat Completions 接口(DeepSeek/通义/Ollama/OpenAI 等)
 *
 * <p>P0 起默认关闭:AgentScopeRunner(Order 100)的 supports 条件与原先完全相同,
 * 本执行器排在后面永不可达。保留为显式开关的备用直连通道,需开启
 * {@code agent.runtime.openai-compat-enabled=true} 才会参与执行器选择。</p>
 *
 * @author ForgeAgent
 */
@Component
@Order(200)
public class OpenAiCompatRunner implements AgentRunner {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final boolean enabled;

    public OpenAiCompatRunner(AgentRuntimeProperties runtimeProperties) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(runtimeProperties.getOpenAiConnectTimeoutSeconds()))
            .build();
        this.requestTimeout = Duration.ofSeconds(runtimeProperties.getOpenAiRequestTimeoutSeconds());
        this.enabled = runtimeProperties.isOpenAiCompatEnabled();
    }

    @Override
    public boolean supports(RunnerContext ctx) {
        return enabled && ctx.llmReady();
    }

    @Override
    public RunnerResult reply(RunnerContext ctx, String content) {
        // 1. 组装消息:系统提示词(含 RAG 检索上下文) + 会话历史 + 本轮用户消息
        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = ctx.systemPrompt();
        if (StringUtils.isNotBlank(ctx.retrievedContext())) {
            systemPrompt = (StringUtils.isBlank(systemPrompt) ? "" : systemPrompt + "\n\n")
                + "以下为知识库检索结果与用户上传文件的相关内容,请结合其中内容回答;若未命中相关内容或内容不足,请明确说明后再基于通用知识回答;"
                + "文件内容不完整时可调用 read_attachment 工具按页/工作表/关键词读取:\n"
                + ctx.retrievedContext();
        }
        // 注入恒定时区说明(不破坏 KV Cache 前缀缓存);动态时间在用户消息中注入
        systemPrompt = (StringUtils.isBlank(systemPrompt) ? "" : systemPrompt + "\n\n")
            + AgentRunner.timeZonePrompt();
        if (StringUtils.isNotBlank(systemPrompt)) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (ctx.history() != null) {
            for (ChatTurn turn : ctx.history()) {
                if (StringUtils.isNotBlank(turn.content())) {
                    messages.add(Map.of("role", turn.role(), "content", turn.content()));
                }
            }
        }
        // 本轮用户消息:携带图片时组装为 OpenAI 多模态 content 数组,否则保持纯文本字符串
        if (ctx.images() == null || ctx.images().isEmpty()) {
            messages.add(Map.of("role", "user", "content", content));
        } else {
            List<Map<String, Object>> parts = new ArrayList<>();
            if (StringUtils.isNotBlank(content)) {
                parts.add(Map.<String, Object>of("type", "text", "text", content));
            }
            for (AgentRunner.ImageRef image : ctx.images()) {
                parts.add(Map.<String, Object>of("type", "image_url", "image_url",
                    Map.of("url", "data:" + image.mediaType() + ";base64," + image.base64())));
            }
            messages.add(Map.of("role", "user", "content", parts));
        }

        // 2. 请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ctx.llmModel());
        body.put("messages", messages);
        if (ctx.temperature() != null) {
            body.put("temperature", ctx.temperature());
        }
        if (ctx.maxTokens() != null) {
            body.put("max_tokens", ctx.maxTokens());
        }
        body.put("stream", false);

        // 3. 调用
        String responseBody;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildChatUrl(ctx.llmBaseUrl())))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ctx.llmApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("模型调用失败(HTTP " + response.statusCode() + "): " + extractError(response.body()));
            }
            responseBody = response.body();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("模型调用失败: " + rootMessage(e));
        }

        // 4. 解析回复与用量
        try {
            JsonNode root = JSON_MAPPER.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (!contentNode.isString() || StringUtils.isBlank(contentNode.asString())) {
                throw new ServiceException("模型返回内容为空: " + extractError(responseBody));
            }
            JsonNode usage = root.path("usage");
            return new RunnerResult(contentNode.asString(),
                usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null,
                usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null,
                usage.path("total_tokens").isNumber() ? usage.path("total_tokens").asInt() : null,
                "openai-compat");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("模型响应解析失败: " + rootMessage(e));
        }
    }

    /**
     * 拼接 Chat Completions 地址:兼容 baseUrl 带或不带 /v1 的情况
     */
    static String buildChatUrl(String baseUrl) {
        String url = StringUtils.stripEnd(baseUrl.trim(), "/");
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        if (url.endsWith("/v1")) {
            return url + "/chat/completions";
        }
        return url + "/v1/chat/completions";
    }

    private String extractError(String body) {
        try {
            JsonNode err = JSON_MAPPER.readTree(body).path("error").path("message");
            if (err.isString() && StringUtils.isNotBlank(err.asString())) {
                return err.asString();
            }
        } catch (Exception ignore) {
            // 原文返回
        }
        return StringUtils.defaultIfBlank(body, "无响应内容");
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
