package com.forgeagent.customerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.service.CsChannelGuard;
import com.forgeagent.customerservice.service.CsStreamService;
import com.forgeagent.customerservice.service.CsVisitorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Chat Completions 兼容端点(P3)。
 *
 * <p>让外部系统用标准 SDK/HTTP 直接接客服:请求体与响应体都是 OpenAI 格式,
 * 内部转换为「访客消息 → 客服回复」,复用现有全部防线(渠道校验、限流、Origin、
 * 敏感词、配额、FAQ、语义缓存、知识检索、转人工)。</p>
 *
 * <p><b>会话映射</b>:优先用 {@code ?conversationId=},不带则按访客找最近一个活着的会话,
 * 都没有才新建;实际使用的会话 id 在响应 {@code system_fingerprint} 里回带
 * ({@code cs-conv-123}),调用方下一轮带上它就能保持上下文。</p>
 *
 * <p><b>鉴权</b>:沿用访客链路——先 {@code POST /chat-api/{code}/sessions} 拿访客令牌,
 * 之后放在 {@code X-Visitor-Token} 头(也接受 {@code Authorization: Bearer})。</p>
 */
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat-api/{channelCode}/v1")
public class CsOpenAiCompatController extends BaseController {

    private static final String OBJECT_CHUNK = "chat.completion.chunk";
    private static final String OBJECT_COMPLETION = "chat.completion";

    private final CsChannelGuard guard;
    private final CsVisitorService visitorService;
    private final CsStreamService streamService;
    private final CsConversationMapper conversationMapper;

    @PostMapping("/chat/completions")
    public void chatCompletions(@PathVariable String channelCode,
                                @RequestParam(required = false) Long conversationId,
                                @RequestBody Map<String, Object> body,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        // 与 /chat-api 其余入口同一套防线,不另起鉴权。
        // 注意:项目内部约定业务失败也返回 HTTP 200 + code,但 OpenAI SDK 只看 HTTP 状态码,
        // 所以这里必须把校验异常翻译成标准状态码,否则外部会把「被拒」当成成功。
        CsChannel channel;
        Long visitorId;
        try {
            channel = guard.require(channelCode);
            guard.checkRateLimit(channel, clientIp(request));
            guard.checkOrigin(channel, request.getHeader("Origin"));
            visitorId = visitorId(channelCode, request);
        } catch (Exception e) {
            writeError(response, 401, e.getMessage(), "invalid_request_error");
            return;
        }

        String content = lastUserContent(body);
        if (content == null || content.isBlank()) {
            writeError(response, 400, "messages 中缺少 user 内容", "invalid_request_error");
            return;
        }
        String model = body.get("model") == null ? "forge-customer-service" : String.valueOf(body.get("model"));
        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        Long cid = resolveConversation(channel, visitorId, conversationId);
        if (cid == null) {
            writeError(response, 500, "会话创建失败", "server_error");
            return;
        }
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long created = Instant.now().getEpochSecond();
        String fingerprint = "cs-conv-" + cid;

        if (stream) {
            writeStream(channel, cid, visitorId, content, model, completionId, created, fingerprint, response);
            return;
        }
        Map<String, Object> result = visitorService.send(channel, cid, visitorId, null, content);
        String reply = result.get("content") == null ? "" : String.valueOf(result.get("content"));
        writeJson(response, completion(model, completionId, created, fingerprint, reply));
    }

    /** 非流式响应体(OpenAI chat.completion)。 */
    private Map<String, Object> completion(String model, String id, long created,
                                           String fingerprint, String reply) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", reply);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", OBJECT_COMPLETION);
        body.put("created", created);
        body.put("model", model);
        body.put("choices", List.of(choice));
        body.put("system_fingerprint", fingerprint);
        return body;
    }

    /**
     * 流式:把客服内部事件(chunk / delta)转成 OpenAI 的 chunk 帧。
     *
     * <p>{@code CsStreamService.stream} 是同步阻塞的,返回后即可收口发 [DONE]。</p>
     */
    private void writeStream(CsChannel channel, Long cid, Long visitorId, String content,
                             String model, String id, long created, String fingerprint,
                             HttpServletResponse response) throws IOException {
        response.setContentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();

        StreamEmitter emitter = new StreamEmitter() {
            @Override
            public void send(String event, Object data) {
                if (!"chat_chunk".equals(event) && !"delta".equals(event)) {
                    return;
                }
                String text = textOf(data);
                if (text == null || text.isEmpty()) {
                    return;
                }
                writeFrame(writer, chunk(id, created, model, fingerprint, text, null));
            }

            @Override
            public void finish() {
                // 收口由外层统一做,避免和 CsStreamService 的 chat_done 重复
            }
        };
        try {
            streamService.stream(channel, cid, visitorId, null, content, emitter);
        } catch (Exception e) {
            writeFrame(writer, errorFrame(id, created, model, e.getMessage()));
            writer.write("data: [DONE]\n\n");
            writer.flush();
            return;
        }
        writeFrame(writer, chunk(id, created, model, fingerprint, "", "stop"));
        writer.write("data: [DONE]\n\n");
        writer.flush();
    }

    private Map<String, Object> chunk(String id, long created, String model, String fingerprint,
                                      String text, String finishReason) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (text != null && !text.isEmpty()) {
            delta.put("content", text);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", OBJECT_CHUNK);
        body.put("created", created);
        body.put("model", model);
        body.put("choices", List.of(choice));
        body.put("system_fingerprint", fingerprint);
        return body;
    }

    private Map<String, Object> errorFrame(String id, long created, String model, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message == null ? "内部错误" : message);
        error.put("type", "server_error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", OBJECT_CHUNK);
        body.put("created", created);
        body.put("model", model);
        body.put("error", error);
        return body;
    }

    private void writeFrame(PrintWriter writer, Map<String, Object> payload) {
        writer.write("data: " + com.forgeagent.common.json.utils.JsonUtils.toJsonString(payload) + "\n\n");
        writer.flush();
    }

    /**
     * 会话定位:显式 id → 访客最近活跃会话 → 新建。
     *
     * <p>按访客复用是为了让多轮上下文连贯——每次新建会话会让模型丢掉前文。</p>
     */
    private Long resolveConversation(CsChannel channel, Long visitorId, Long conversationId) {
        if (conversationId != null) {
            CsConversation conv = conversationMapper.selectById(conversationId);
            if (conv != null && conv.getVisitorId().equals(visitorId)
                && conv.getWorkspaceId().equals(channel.getWorkspaceId())) {
                return conv.getId();
            }
        }
        CsConversation active = conversationMapper.selectOne(new LambdaQueryWrapper<CsConversation>()
            .eq(CsConversation::getVisitorId, visitorId)
            .eq(CsConversation::getWorkspaceId, channel.getWorkspaceId())
            .in(CsConversation::getStatus, "ACTIVE", "AI_SERVING")
            .orderByDesc(CsConversation::getId)
            .last("limit 1"));
        if (active != null) {
            return active.getId();
        }
        Map<String, Object> session = visitorService.createSession(channel, "openai-" + visitorId);
        Object cid = session.get("conversationId");
        return cid == null ? null : Long.valueOf(String.valueOf(cid));
    }

    /** 取最后一条 user 消息的内容(兼容 content 为字符串或分段数组)。 */
    @SuppressWarnings("unchecked")
    private static String lastUserContent(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list)) {
            return null;
        }
        String found = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> msg)) {
                continue;
            }
            if (!"user".equals(String.valueOf(msg.get("role")))) {
                continue;
            }
            Object content = msg.get("content");
            if (content instanceof String text) {
                found = text;
            } else if (content instanceof List<?> parts) {
                StringBuilder sb = new StringBuilder();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> node && node.get("text") != null) {
                        sb.append(node.get("text"));
                    }
                }
                found = sb.toString();
            }
        }
        return found;
    }

    private static String textOf(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object value = map.containsKey("content") ? map.get("content") : map.get("text");
            return value == null ? null : String.valueOf(value);
        }
        return data == null ? null : String.valueOf(data);
    }

    private void writeJson(HttpServletResponse response, Map<String, Object> payload) throws IOException {
        response.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.getWriter().write(com.forgeagent.common.json.utils.JsonUtils.toJsonString(payload));
        response.getWriter().flush();
    }

    /** 错误也用 OpenAI 的错误结构,方便标准 SDK 解析。 */
    private void writeError(HttpServletResponse response, int status, String message, String type)
        throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        response.setStatus(status);
        writeJson(response, payload);
    }

    private Long visitorId(String channelCode, HttpServletRequest request) {
        String token = request.getHeader("X-Visitor-Token");
        if (token == null || token.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7);
            }
        }
        if (token == null || token.isBlank()) {
            throw new com.forgeagent.common.core.exception.ServiceException("缺少访客令牌");
        }
        return guard.verifyVisitorToken(token, channelCode);
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        int comma = ip == null ? -1 : ip.indexOf(',');
        return comma > 0 ? ip.substring(0, comma).trim() : ip;
    }
}
