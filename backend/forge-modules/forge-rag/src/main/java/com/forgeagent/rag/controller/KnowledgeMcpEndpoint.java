package com.forgeagent.rag.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库检索 MCP 端点(无状态优先的 Streamable HTTP,POST 单端点返回纯 JSON)
 *
 * <p>协议实现覆盖:MCP-Session-Id 会话签发(宽松校验)与过期回收、
 * 协议版本协商(2025-06-18 / 2025-03-26)、JSON-RPC 批处理、
 * 解析错误 -32700 / 方法不存在 -32601 / 参数错误 -32602 的规范错误码、
 * 工具调用的 structuredContent 结构化输出,以及 GET/DELETE 的 405 响应。
 * 不引 spring-ai-mcp-server 的原因:其 victools 5.0 与 AgentScope 传递的
 * victools 4.38 冲突不可共存(见 application.yml 注释)。</p>
 *
 * <p>安全:默认关闭(rag.knowledge.mcp-enabled=false);开启后 sa-token 放行 /mcp/**,
 * 建议同时配置 rag.knowledge.mcp-token,客户端以 X-MCP-Token 请求头携带
 * (比较采用常量时间算法),网络层再限制来源。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/mcp")
public class KnowledgeMcpEndpoint {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");
    private static final String SERVER_NAME = "forgeagent-knowledge";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String TOOL_NAME = "knowledge_search";
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MAX_TOP_K = 50;

    /**
     * 请求体大小上限(防超大 body DoS)
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final KnowledgeProperties properties;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final IAgentKnowledgeService knowledgeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 已初始化会话:sessionId -> (协议版本, 最近访问时间),过期由定时任务回收。
     * 宽松策略:未携带会话头的请求按匿名会话放行,过期会话返回 404 提示重新初始化
     */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    private record McpSession(String protocolVersion, long lastAccess) {
    }

    // ==================== 传输层 ====================

    @PostMapping
    public ResponseEntity<String> handlePost(
        @RequestHeader(value = "X-MCP-Token", required = false) String token,
        @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
        @RequestBody String rawBody) {

        if (!Boolean.TRUE.equals(properties.getMcpEnabled())) {
            return notFound();
        }
        if (!tokenOk(token)) {
            return jsonResponse(HttpStatus.UNAUTHORIZED, error(null, -32001, "无效的 MCP 访问令牌"), null, null);
        }
        if (rawBody.length() > MAX_BODY_BYTES) {
            return jsonResponse(HttpStatus.BAD_REQUEST, error(null, -32000, "请求体过大"), null, null);
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return jsonResponse(HttpStatus.BAD_REQUEST, error(null, -32700, "JSON 解析失败"), null, null);
        }

        // JSON-RPC 批处理(2025-03-26 允许,2025-06-18 移除;宽容支持)
        if (body.isArray()) {
            List<String> responses = new ArrayList<>();
            boolean sawNotification = false;
            for (JsonNode item : body) {
                if (item.path("id").isMissingNode()
                    || item.path("method").asText("").startsWith("notifications/")) {
                    sawNotification = true;
                    continue;
                }
                ResponseEntity<String> one = handleSingle(item, sessionId);
                if (one.getBody() != null && StringUtils.isNotBlank(one.getBody())) {
                    responses.add(one.getBody());
                }
            }
            return responses.isEmpty()
                ? (sawNotification ? ResponseEntity.accepted().build()
                    : jsonResponse(HttpStatus.OK, objectMapper.createObjectNode(), sessionId, null))
                : jsonResponse(HttpStatus.OK, arrayNode(responses), sessionId, null);
        }

        // 通知类消息无 id,按规范回 202 空体
        if (body.path("id").isMissingNode()
            || body.path("method").asText("").startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }
        return handleSingle(body, sessionId);
    }

    @GetMapping
    public ResponseEntity<String> handleGet() {
        // 服务器不提供服务器发起的 SSE 流,拒绝 GET
        return methodNotAllowed();
    }

    @DeleteMapping
    public ResponseEntity<String> handleDelete() {
        // 无状态实现无会话可终止
        return methodNotAllowed();
    }

    /**
     * 过期会话回收(每 5 分钟)
     */
    @Scheduled(fixedDelay = 300_000)
    public void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().lastAccess() > SESSION_TTL_MS);
    }

    // ==================== 单请求处理 ====================

    private ResponseEntity<String> handleSingle(JsonNode body, String sessionIdHeader) {
        String method = body.path("method").asText("");
        boolean isNotification = body.path("id").isMissingNode() || body.path("id").isNull();
        JsonNode id = isNotification ? null : body.path("id").deepCopy();

        if (isNotification || method.startsWith("notifications/")) {
            // 通知类消息无响应体,按规范回 202
            return ResponseEntity.accepted().build();
        }
        if (method.isEmpty()) {
            return jsonResponse(HttpStatus.OK, error(id, -32600, "缺少 method 字段"), sessionIdHeader, null);
        }
        if (StringUtils.isNotBlank(sessionIdHeader) && !sessions.containsKey(sessionIdHeader)) {
            // 已签发会话过期:回 404 提示客户端重新 initialize
            return jsonResponse(HttpStatus.NOT_FOUND,
                error(id, -32001, "会话不存在或已过期,请重新 initialize"), null, null);
        }
        // 刷新最近访问时间(record 不可变,整体替换)
        McpSession known = StringUtils.isBlank(sessionIdHeader) ? null : sessions.get(sessionIdHeader);
        if (known != null) {
            sessions.put(sessionIdHeader, new McpSession(known.protocolVersion(), System.currentTimeMillis()));
        }

        ResponseEntity<String> resp;
        switch (method) {
            case "initialize" -> resp = handleInitialize(id, body);
            case "ping" -> resp = okResponse(id, objectMapper.createObjectNode(), sessionIdHeader);
            case "tools/list" -> resp = okResponse(id,
                objectMapper.createObjectNode().set("tools", toolDefs()), sessionIdHeader);
            case "tools/call" -> resp = handleToolCall(id, body, sessionIdHeader);
            default -> resp = jsonResponse(HttpStatus.OK, error(id, -32601, "未知方法:" + method),
                sessionIdHeader, null);
        }

        if ("tools/call".equals(method) && log.isDebugEnabled()) {
            log.debug("[MCP] tools/call 完成, session={}", sessionIdHeader);
        }
        return resp;
    }

    private ResponseEntity<String> handleInitialize(JsonNode id, JsonNode body) {
        String clientVersion = body.path("params").path("protocolVersion").asText("");
        // 版本协商:客户端版本受支持则沿用,否则回退到服务端最新支持版本
        String negotiated = SUPPORTED_PROTOCOL_VERSIONS.contains(clientVersion)
            ? clientVersion : PROTOCOL_VERSION;

        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new McpSession(negotiated, System.currentTimeMillis()));

        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        result.putObject("capabilities")
            .putObject("tools")
            .put("listChanged", false);
        result.set("serverInfo", serverInfo);
        result.put("instructions",
            "检索 ForgeAgent 平台知识库。先用 knowledge_list 查看可用知识库,"
                + "再用 knowledge_search 按问题召回相关文档片段。");

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id.deepCopy());
        resp.set("result", result);
        return ResponseEntity.ok()
            .header("Mcp-Session-Id", sessionId)
            .header("MCP-Protocol-Version", negotiated)
            .contentType(MediaType.APPLICATION_JSON)
            .body(toJson(resp));
    }

    private ResponseEntity<String> handleToolCall(JsonNode id, JsonNode body, String sessionIdHeader) {
        String toolName = body.path("params").path("name").asText("");
        JsonNode args = body.path("params").path("arguments");
        McpSession session = StringUtils.isBlank(sessionIdHeader) ? null : sessions.get(sessionIdHeader);
        String protocolVersion = session == null ? null : session.protocolVersion();

        ObjectNode toolResult;
        try {
            toolResult = switch (toolName) {
                case "knowledge_search" -> knowledgeSearchTool(args);
                case "knowledge_list" -> knowledgeListTool();
                default -> toolError("未知工具:" + toolName);
            };
        } catch (IllegalArgumentException e) {
            // 业务校验类错误(参数缺失/知识库不存在等),消息可安全透出
            log.warn("[MCP] 工具 {} 参数或状态错误: {}", toolName, e.getMessage());
            toolResult = toolError(e.getMessage());
        } catch (Exception e) {
            // 非业务异常的内部细节只落服务端日志,对客户端脱敏
            log.warn("[MCP] 工具 {} 执行失败: {}", toolName, e.getMessage());
            toolResult = toolError("工具执行失败,请稍后重试");
        }
        boolean isError = toolResult.path("isError").asBoolean(false);
        boolean structuredCapable = !("2025-03-26".equals(protocolVersion) || protocolVersion == null);

        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        // 错误结果透出工具的错误消息;正常结果输出 rows 数组的 JSON 文本
        String textBody = isError
            ? toolResult.path("content").isEmpty()
                ? "工具执行失败" : toolResult.path("content").get(0).path("text").asText("工具执行失败")
            : toJson(toolResult.path("rows"));
        textContent.put("text", textBody);
        ArrayNode content = objectMapper.createArrayNode();
        content.add(textContent);
        result.set("content", content);
        result.put("isError", isError);
        if (structuredCapable && !isError) {
            ObjectNode structured = objectMapper.createObjectNode();
            structured.set("rows", toolResult.path("rows").deepCopy());
            result.set("structuredContent", structured);
        }

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id.deepCopy());
        resp.set("result", result);

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.isNotBlank(sessionIdHeader)) {
            builder.header("Mcp-Session-Id", sessionIdHeader);
        }
        if (StringUtils.isNotBlank(protocolVersion)) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        return builder.body(toJson(resp));
    }

    // ==================== 工具实现 ====================

    /**
     * 工具定义:knowledge_search + knowledge_list
     */
    private ArrayNode toolDefs() {
        ObjectNode props = objectMapper.createObjectNode();
        props.putObject("query").put("type", "string")
            .put("description", "检索问题,尽量具体");
        props.putObject("knowledgeId").put("type", "integer")
            .put("description", "可选,限定知识库ID;缺省时检索全部启用的知识库");
        props.putObject("topK").put("type", "integer")
            .put("description", "可选,每个知识库返回条数,默认 5");

        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "在 ForgeAgent 平台知识库中检索与问题最相关的文档片段,"
            + "返回文件名/页码/相关度分与片段内容");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", props);
        ArrayNode required = schema.putArray("required");
        required.add("query");

        ObjectNode listTool = objectMapper.createObjectNode();
        listTool.put("name", "knowledge_list");
        listTool.put("description", "列出平台启用的知识库(名称/文档数/描述),用于获取 knowledge_search 的 knowledgeId");
        ObjectNode listSchema = listTool.putObject("inputSchema");
        listSchema.put("type", "object");

        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(tool);
        tools.add(listTool);
        return tools;
    }

    /**
     * 访问令牌校验(常量时间比较,防时序侧信道)
     */
    private boolean tokenOk(String token) {
        String expected = properties.getMcpToken();
        if (StringUtils.isBlank(expected)) {
            return true;
        }
        if (StringUtils.isBlank(token)) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode knowledgeSearchTool(JsonNode args) {
        String query = args.path("query").asText("");
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH);
        }
        int topK = Math.min(Math.max(args.path("topK").asInt(5), 1), MAX_TOP_K);
        boolean hasKb = args.hasNonNull("knowledgeId");
        long start = System.currentTimeMillis();

        List<AgentKnowledge> targets = new ArrayList<>();
        if (hasKb) {
            AgentKnowledge kb = knowledgeMapper.selectById(args.get("knowledgeId").asLong());
            if (kb == null || !"0".equals(kb.getStatus())) {
                throw new IllegalArgumentException("知识库不存在或已停用");
            }
            targets.add(kb);
        } else {
            targets = knowledgeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentKnowledge>()
                    .eq(AgentKnowledge::getStatus, "0")
                    .eq(AgentKnowledge::getKbScope, "normal"));
        }

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        int totalHits = 0;
        for (AgentKnowledge kb : targets) {
            try {
                KnowledgeSearchBo bo = new KnowledgeSearchBo();
                bo.setKnowledgeId(kb.getId());
                bo.setQuery(query);
                bo.setTopK(topK);
                bo.setCacheable(Boolean.TRUE);
                for (KnowledgeSearchHitVo hit : knowledgeService.search(bo)) {
                    ObjectNode row = rows.addObject();
                    row.put("knowledge", kb.getName());
                    row.put("knowledgeId", String.valueOf(kb.getId()));
                    row.put("file", hit.getFileName());
                    row.put("chunkIndex", hit.getChunkIndex());
                    if (StringUtils.isNotBlank(hit.getPageLabel())) {
                        row.put("location", hit.getPageLabel());
                    }
                    row.put("score", hit.getRerankScore() != null ? hit.getRerankScore() : hit.getScore());
                    row.put("content", hit.getContent());
                    totalHits++;
                }
            } catch (Exception e) {
                log.warn("[MCP] 知识库 {} 检索失败: {}", kb.getId(), e.getMessage());
                ObjectNode err = rows.addObject();
                err.put("knowledge", kb.getName());
                err.put("error", "检索失败:" + e.getMessage());
            }
        }
        result.put("query", query);
        result.put("totalHits", totalHits);
        result.put("durationMs", System.currentTimeMillis() - start);
        if (totalHits == 0) {
            result.put("hint", "未命中相关内容,可尝试更具体的问题或检查知识库是否包含相关文档");
        }
        return result;
    }

    private ObjectNode knowledgeListTool() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        List<AgentKnowledge> kbs = knowledgeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentKnowledge>()
                .eq(AgentKnowledge::getStatus, "0")
                .eq(AgentKnowledge::getKbScope, "normal"));
        for (AgentKnowledge kb : kbs) {
            ObjectNode row = rows.addObject();
            row.put("knowledgeId", String.valueOf(kb.getId()));
            row.put("name", kb.getName());
            row.put("docCount", kb.getDocCount() == null ? 0 : kb.getDocCount());
            row.put("description", StringUtils.defaultString(kb.getDescription()));
        }
        result.put("hint", "将 knowledgeId 传入 knowledge_search 可限定检索范围");
        return result;
    }

    private ObjectNode toolError(String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("isError", true);
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message);
        return result;
    }

    // ==================== 响应构造 ====================

    private ResponseEntity<String> okResponse(JsonNode id, JsonNode result, String sessionIdHeader) {
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) {
            resp.set("id", id.deepCopy());
        }
        resp.set("result", result);
        return jsonResponse(HttpStatus.OK, resp, sessionIdHeader, sessionVersion(sessionIdHeader));
    }

    private ResponseEntity<String> jsonResponse(HttpStatus status, JsonNode body, String sessionId,
                                                String protocolVersion) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.isNotBlank(sessionId)) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        String version = protocolVersion != null ? protocolVersion : sessionVersion(sessionId);
        if (StringUtils.isNotBlank(version)) {
            builder.header("MCP-Protocol-Version", version);
        }
        return builder.body(toJson(body));
    }

    private ResponseEntity<String> methodNotAllowed() {
        return ResponseEntity.status(405)
            .header("Allow", "POST")
            .body(toJson(error(null, -32000, "本端点仅接受 POST")));
    }

    private String sessionVersion(String sessionId) {
        McpSession s = StringUtils.isBlank(sessionId) ? null : sessions.get(sessionId);
        return s == null ? null : s.protocolVersion();
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode err = objectMapper.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            resp.set("id", id.deepCopy());
        }
        resp.set("error", err);
        return resp;
    }

    private ArrayNode arrayNode(List<String> jsonValues) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (String v : jsonValues) {
            try {
                arr.add(objectMapper.readTree(v));
            } catch (Exception ignore) {
                // 单条批处理响应解析失败时跳过
            }
        }
        return arr;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private ResponseEntity<String> notFound() {
        return ResponseEntity.status(404)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"MCP 端点未启用\"}");
    }
}
