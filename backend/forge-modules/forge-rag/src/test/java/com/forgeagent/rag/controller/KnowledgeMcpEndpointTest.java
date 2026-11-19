package com.forgeagent.rag.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeagent.rag.config.KnowledgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 端点协议一致性测试:不启动 Spring 上下文,直接构造端点验证
 * 握手/会话/版本协商/工具发现与调用/错误码/安全默认值
 *
 * @author ForgeAgent
 */
@Tag("dev")
class KnowledgeMcpEndpointTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private KnowledgeProperties properties;
    private KnowledgeMcpEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeProperties();
        properties.setMcpEnabled(true);
        // mapper/service 传 null:协议层用例不触达检索实现,
        // tools/call 的检索路径走参数校验失败与异常脱敏分支,同样可断言
        endpoint = new KnowledgeMcpEndpoint(properties, null, null, null, java.util.List.of(),
            java.util.List.of(), null);
    }

    private ResponseEntity<String> post(String body) {
        return endpoint.handlePost(null, null, body);
    }

    private JsonNode parse(String body) throws Exception {
        return mapper.readTree(body);
    }

    /** 完成握手,返回会话头 */
    private String initialize() {
        ResponseEntity<String> resp = endpoint.handlePost(null, null,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return resp.getHeaders().getFirst("Mcp-Session-Id");
    }

    // ==================== 安全默认值 ====================

    @Test
    @DisplayName("默认关闭时所有请求回 404(隐藏端点存在性)")
    void disabledEndpointReturns404() {
        properties.setMcpEnabled(false);
        ResponseEntity<String> resp = post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    @DisplayName("配置令牌后缺头/错头回 401")
    void tokenMismatchReturns401() {
        properties.setMcpToken("secret-token");
        ResponseEntity<String> missing = endpoint.handlePost(null, null,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode());

        ResponseEntity<String> wrong = endpoint.handlePost("wrong-token", null,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, wrong.getStatusCode());

        ResponseEntity<String> ok = endpoint.handlePost("secret-token", null,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertEquals(HttpStatus.OK, ok.getStatusCode());
    }

    @Test
    @DisplayName("请求体超过 1MB 上限回 -32000")
    void oversizedBodyRejected() {
        StringBuilder big = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\"");
        big.append("x".repeat(1024 * 1024));
        big.append("\"}}");
        ResponseEntity<String> resp = post(big.toString());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(parseSafe(resp.getBody()).path("error").path("code").asInt() == -32000);
    }

    // ==================== 握手与会话 ====================

    @Test
    @DisplayName("initialize 返回协议版本/服务端信息并签发会话头")
    void initializeHandshake() throws Exception {
        ResponseEntity<String> resp = post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode result = parse(resp.getBody()).path("result");
        assertEquals("2025-06-18", result.path("protocolVersion").asText());
        assertEquals("forgeagent-platform", result.path("serverInfo").path("name").asText());
        assertTrue(result.path("capabilities").path("tools").isObject());
        assertNotNull(resp.getHeaders().getFirst("Mcp-Session-Id"));
    }

    @Test
    @DisplayName("版本协商:客户端旧版本被沿用,未知版本回退服务端最新")
    void versionNegotiation() throws Exception {
        ResponseEntity<String> old = post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertEquals("2025-03-26", parse(old.getBody()).path("result").path("protocolVersion").asText());

        ResponseEntity<String> unknown = post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals("2025-06-18", parse(unknown.getBody()).path("result").path("protocolVersion").asText());
    }

    @Test
    @DisplayName("携带过期会话头的请求回 404 提示重新初始化")
    void staleSessionReturns404() {
        ResponseEntity<String> resp = endpoint.handlePost(null, "expired-session",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ==================== 工具层 ====================

    @Test
    @DisplayName("tools/list 声明 knowledge_search 与 knowledge_list,schema 含必填 query")
    void toolsList() throws Exception {
        ResponseEntity<String> resp = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode tools = parse(resp.getBody()).path("result").path("tools");
        assertEquals(3, tools.size());
        assertEquals("knowledge_search", tools.get(0).path("name").asText());
        assertEquals("knowledge_list", tools.get(1).path("name").asText());
        assertEquals("knowledge_chunks", tools.get(2).path("name").asText());
        JsonNode schema = tools.get(0).path("inputSchema");
        assertEquals("object", schema.path("type").asText());
        assertEquals("query", schema.path("required").get(0).asText());
        // R5-6 filters 已暴露
        assertEquals("object", schema.path("properties").path("filters").path("type").asText());
    }

    @Test
    @DisplayName("tools/call 未知工具回 isError 结果")
    void unknownToolCall() throws Exception {
        ResponseEntity<String> resp = post(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
        System.out.println("DEBUG-unknownToolCall: " + resp.getBody());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode result = parse(resp.getBody()).path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("未知工具"));
    }

    @Test
    @DisplayName("tools/call 检索异常时脱敏:不透出内部异常类名")
    void toolCallErrorSanitized() throws Exception {
        ResponseEntity<String> resp = post(
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"knowledge_search\",\"arguments\":{\"query\":\"测试\"}}}");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode result = parse(resp.getBody()).path("result");
        assertTrue(result.path("isError").asBoolean());
        String text = result.path("content").get(0).path("text").asText();
        assertFalse(text.contains("NullPointerException"));
        assertTrue(text.contains("工具执行失败"));
    }

    // ==================== 协议错误码 ====================

    @Test
    @DisplayName("JSON 解析失败回 -32700")
    void parseError() {
        ResponseEntity<String> resp = post("{not-json");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(-32700, parseSafe(resp.getBody()).path("error").path("code").asInt());
    }

    @Test
    @DisplayName("未知方法回 -32601,缺 method 回 -32600")
    void protocolErrors() {
        ResponseEntity<String> unknown = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}");
        assertEquals(-32601, parseSafe(unknown.getBody()).path("error").path("code").asInt());

        ResponseEntity<String> noMethod = post("{\"jsonrpc\":\"2.0\",\"id\":6}");
        assertEquals(-32600, parseSafe(noMethod.getBody()).path("error").path("code").asInt());
    }

    @Test
    @DisplayName("ping 返回空 result")
    void ping() {
        ResponseEntity<String> resp = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(parseSafe(resp.getBody()).path("result").isObject());
    }

    // ==================== 通知与批处理 ====================

    @Test
    @DisplayName("通知类消息回 202 空体")
    void notificationAccepted() {
        ResponseEntity<String> resp = post(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
    }

    @Test
    @DisplayName("批处理:请求与通知混合时仅请求产生响应,保持顺序")
    void batchHandling() throws Exception {
        String batch = "["
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
            + "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"},"
            + "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}"
            + "]";
        ResponseEntity<String> resp = post(batch);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode arr = parse(resp.getBody());
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
        assertEquals(10, arr.get(0).path("id").asInt());
        assertTrue(arr.get(0).path("result").isObject());
        assertEquals(11, arr.get(1).path("id").asInt());
        assertTrue(arr.get(1).path("result").path("tools").isArray());
    }

    // ==================== 传输层 ====================

    @Test
    @DisplayName("GET/DELETE 回 405")
    void getAndDeleteNotAllowed() {
        assertEquals(405, endpoint.handleGet().getStatusCode().value());
        assertEquals(405, endpoint.handleDelete().getStatusCode().value());
    }

    // ==================== 辅助 ====================

    private JsonNode parseSafe(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }


    // ==================== 08方案 / A5:外部工具 SPI ====================

    private com.forgeagent.rag.mcp.McpExternalToolProvider stubProvider() {
        com.forgeagent.rag.mcp.McpExternalToolProvider provider =
            org.mockito.Mockito.mock(com.forgeagent.rag.mcp.McpExternalToolProvider.class);
        ObjectNode def = mapper.createObjectNode();
        def.put("name", "skill_list");
        def.put("description", "列出平台技能");
        def.putObject("inputSchema").put("type", "object");
        org.mockito.Mockito.when(provider.supportedTools()).thenReturn(java.util.Set.of("skill_list", "skill_get"));
        org.mockito.Mockito.when(provider.definitions(org.mockito.Mockito.any()))
            .thenReturn(java.util.List.of((JsonNode) def));
        org.mockito.Mockito.when(provider.scope()).thenReturn("skill");
        return provider;
    }

    @Test
    @DisplayName("A5:外部工具定义进入 tools/list")
    void externalToolDefinitionAppearsInToolsList() throws Exception {
        endpoint = new KnowledgeMcpEndpoint(properties, null, null, null, java.util.List.of(stubProvider()),
            java.util.List.of(), null);
        String sessionId = initialize();
        ResponseEntity<String> resp = endpoint.handlePost(null, sessionId,
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = parse(resp.getBody()).path("result").path("tools");
        boolean found = false;
        for (JsonNode t : tools) {
            if ("skill_list".equals(t.path("name").asText())) {
                found = true;
            }
        }
        assertTrue(found, "外部 Provider 的 skill_list 必须出现在 tools/list");
    }

    @Test
    @DisplayName("A5:tools/call 路由到外部 Provider 并透传 rows")
    void externalToolCallRoutesToProvider() throws Exception {
        com.forgeagent.rag.mcp.McpExternalToolProvider provider = stubProvider();
        ObjectNode result = mapper.createObjectNode();
        ObjectNode rows = result.putArray("rows").addObject();
        rows.put("name", "退换货处理");
        org.mockito.Mockito.when(provider.invoke(org.mockito.Mockito.any(),
                org.mockito.Mockito.eq("skill_get"), org.mockito.Mockito.any(),
                org.mockito.Mockito.any()))
            .thenReturn(result);
        endpoint = new KnowledgeMcpEndpoint(properties, null, null, null, java.util.List.of(provider),
            java.util.List.of(), null);

        String sessionId = initialize();
        ResponseEntity<String> resp = endpoint.handlePost(null, sessionId,
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"skill_get\",\"arguments\":{\"name\":\"退换货处理\"}}}");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = parse(resp.getBody());
        assertFalse(body.path("result").path("isError").asBoolean(true), "外部工具正常执行不得报错");
        assertTrue(body.path("result").path("structuredContent").path("rows").get(0)
            .path("name").asText("").contains("退换货"), "rows 结构化输出透传");
    }

    // ---------------- A5 增强:令牌 -> 工作空间 + 授权域 ----------------

    /** 构造一个"只认 fgm_test_token"的端点 */
    private KnowledgeMcpEndpoint scopedEndpoint(com.forgeagent.rag.mcp.McpCaller caller) {
        com.forgeagent.rag.mcp.McpClientAuthenticator auth = token ->
            "fgm_test_token".equals(token)
                ? java.util.Optional.of(caller) : java.util.Optional.empty();
        return new KnowledgeMcpEndpoint(properties, null, null, null, java.util.List.of(stubProvider()),
            java.util.List.of(auth), null);
    }

    @Test
    @DisplayName("A5 增强:授权域为 skill 时 tools/list 不暴露 knowledge 工具")
    void scopedClientOnlySeesAllowedTools() throws Exception {
        endpoint = scopedEndpoint(new com.forgeagent.rag.mcp.McpCaller("DSH", 100L,
            java.util.Set.of(com.forgeagent.rag.mcp.McpCaller.SCOPE_SKILL)));

        ResponseEntity<String> resp = endpoint.handlePost("fgm_test_token", null,
            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}");

        JsonNode tools = parse(resp.getBody()).path("result").path("tools");
        boolean knowledge = false;
        boolean skill = false;
        for (JsonNode node : tools) {
            if ("knowledge_search".equals(node.path("name").asText())) {
                knowledge = true;
            }
            if ("skill_list".equals(node.path("name").asText())) {
                skill = true;
            }
        }
        assertFalse(knowledge, "未授权 knowledge 域时不得暴露检索工具");
        assertTrue(skill, "授权 skill 域后外部工具仍可见");
    }

    @Test
    @DisplayName("A5 增强:越域调用 knowledge_search 返回未授权而非执行")
    void scopedClientCannotCallKnowledgeTools() throws Exception {
        endpoint = scopedEndpoint(new com.forgeagent.rag.mcp.McpCaller("DSH", 100L,
            java.util.Set.of(com.forgeagent.rag.mcp.McpCaller.SCOPE_SKILL)));

        ResponseEntity<String> resp = endpoint.handlePost("fgm_test_token", null,
            "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"knowledge_search\",\"arguments\":{\"query\":\"退换货\"}}}");

        JsonNode body = parse(resp.getBody());
        assertTrue(body.path("result").path("isError").asBoolean(false), "越域调用必须报错");
        assertTrue(body.path("result").path("content").get(0).path("text").asText("").contains("未授权"));
    }

    @Test
    @DisplayName("A5 增强:启用客户端表后旧单 token 仍可用(回落不破坏既有部署)")
    void legacyTokenStillAcceptedAlongsideClients() throws Exception {
        properties.setMcpToken("legacy-secret");
        endpoint = scopedEndpoint(com.forgeagent.rag.mcp.McpCaller.platformWide("legacy"));

        ResponseEntity<String> resp = endpoint.handlePost("legacy-secret", null,
            "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/list\"}");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(parse(resp.getBody()).path("result").path("tools").size() >= 3,
            "平台全量身份应看到全部内置工具");
    }

    @Test
    @DisplayName("A5 增强:平台令牌停用后拒绝,不得回落成不校验")
    void disabledClientTokenIsRejectedNotFallenBack() {
        // 回落条件故意满足(mcpToken 为空即放行):若实现退化成回落,这个用例会挂
        properties.setMcpToken("");
        com.forgeagent.rag.mcp.McpClientAuthenticator auth =
            new com.forgeagent.rag.mcp.McpClientAuthenticator() {
                @Override
                public java.util.Optional<com.forgeagent.rag.mcp.McpCaller> authenticate(String token) {
                    return java.util.Optional.empty();
                }

                @Override
                public boolean managed(String token) {
                    return token != null && token.startsWith("fgm_");
                }
            };
        endpoint = new KnowledgeMcpEndpoint(properties, null, null, null, java.util.List.of(),
            java.util.List.of(auth), null);

        ResponseEntity<String> resp = endpoint.handlePost("fgm_disabled_or_rotated", null,
            "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/list\"}");

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
            "归客户端表管辖但校验失败时必须拒绝,不能回落放行");
    }

    @Test
    @DisplayName("A5 增强:未知令牌仍被 401 拒绝")
    void unknownTokenIsRejectedWhenClientsConfigured() {
        properties.setMcpToken("legacy-secret");
        endpoint = scopedEndpoint(com.forgeagent.rag.mcp.McpCaller.platformWide("legacy"));

        ResponseEntity<String> resp = endpoint.handlePost("not-a-valid-token", null,
            "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/list\"}");

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("A5:无 Provider 支持时保持未知工具错误语义")
    void unknownToolStaysUnknownWithoutProvider() throws Exception {
        endpoint = new KnowledgeMcpEndpoint(properties, null, null, null, java.util.List.of(),
            java.util.List.of(), null);
        String sessionId = initialize();
        ResponseEntity<String> resp = endpoint.handlePost(null, sessionId,
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"no_such\",\"arguments\":{}}}");
        assertTrue(parse(resp.getBody()).path("result").path("isError").asBoolean(false),
            "未知工具返回 isError=true");
    }
}
