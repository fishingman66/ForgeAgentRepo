package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 08方案 / A4:MCP 连接池契约单测。
 *
 * <p>锁定三条行为:指纹对配置敏感且与 headers 键序无关;命中后经 listTools 校验;
 * 校验失败驱逐并关闭连接,下次 acquire 返回 null 交由装配层自愈。</p>
 */
@Tag("dev")
class McpConnectionPoolTest {

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, "测试工具",
            Map.of("type", "object", "properties", Map.of()), null, null, null);
    }

    private static McpClientWrapper wrapper(List<McpSchema.Tool> tools) {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.listTools()).thenReturn(tools == null ? Mono.empty() : Mono.just(tools));
        return wrapper;
    }

    private static ToolAssembler.McpDef def(String serverType, String url, String headers, Integer timeout) {
        return new ToolAssembler.McpDef(1L, "srv", serverType, url, headers, timeout, null, null);
    }

    @Test
    void fingerprintIsSensitiveToAllConfigFields() {
        McpConnectionPool pool = new McpConnectionPool(new AgentChatProperties());
        String base = pool.fingerprint(def("streamable_http", "http://a/mcp", null, 30));
        assertEquals(base, pool.fingerprint(def("streamable_http", "http://a/mcp", null, 30)),
            "同配置必须同指纹");
        assertNotEquals(base, pool.fingerprint(def("sse", "http://a/mcp", null, 30)), "serverType 变指纹变");
        assertNotEquals(base, pool.fingerprint(def("streamable_http", "http://b/mcp", null, 30)), "url 变指纹变");
        assertNotEquals(base, pool.fingerprint(def("streamable_http", "http://a/mcp", null, 60)), "超时变指纹变");
        assertNotEquals(base, pool.fingerprint(def("streamable_http", "http://a/mcp", "{\"k\":\"v\"}", 30)),
            "headers 变指纹变");
    }

    @Test
    void fingerprintIgnoresHeaderKeyOrder() {
        McpConnectionPool pool = new McpConnectionPool(new AgentChatProperties());
        String a = pool.fingerprint(def("streamable_http", "http://a/mcp", "{\"x\":\"1\",\"y\":\"2\"}", 30));
        String b = pool.fingerprint(def("streamable_http", "http://a/mcp", "{\"y\":\"2\",\"x\":\"1\"}", 30));
        assertEquals(a, b, "headers JSON 键序不影响指纹");
    }

    @Test
    void acquireMissReturnsNull() {
        McpConnectionPool pool = new McpConnectionPool(new AgentChatProperties());
        assertNull(pool.acquire("not-present"));
    }

    @Test
    void offerThenAcquireHitsAndRevalidates() {
        McpConnectionPool pool = new McpConnectionPool(new AgentChatProperties());
        String fp = pool.fingerprint(def(null, "http://a/mcp", null, null));
        McpClientWrapper wrapper = wrapper(List.of(tool("t1")));
        pool.offer(fp, new McpConnectionPool.Pooled(wrapper, List.of(tool("t1"))));

        McpConnectionPool.Pooled pooled = pool.acquire(fp);
        assertNotNull(pooled, "offer 后必须命中");
        assertEquals("t1", pooled.tools().get(0).name());
        // 命中会再调一次 listTools 做健康校验
        verify(wrapper, times(1)).listTools();
    }

    @Test
    void emptyToolsAreNeverPooled() {
        McpConnectionPool pool = new McpConnectionPool(new AgentChatProperties());
        String fp = pool.fingerprint(def(null, "http://a/mcp", null, null));
        pool.offer(fp, new McpConnectionPool.Pooled(wrapper(null), List.of()));
        assertNull(pool.acquire(fp), "空工具列表不得入池");
    }

    @Test
    void failedValidationEvictsAndCloses() {
        McpConnectionPool pool = new McpConnectionPool(new AgentChatProperties());
        String fp = pool.fingerprint(def(null, "http://a/mcp", null, null));
        // 第一次 listTools 健康,之后持续失败(模拟服务端下线)
        McpClientWrapper dead = mock(McpClientWrapper.class);
        when(dead.listTools()).thenReturn(Mono.just(List.of(tool("t1"))), Mono.empty());
        pool.offer(fp, new McpConnectionPool.Pooled(dead, List.of(tool("t1"))));

        assertNotNull(pool.acquire(fp), "首次 acquire 校验通过,应命中");
        assertNull(pool.acquire(fp), "校验失败必须驱逐并返回 null");
        verify(dead, times(1)).close();
        assertNull(pool.acquire(fp), "驱逐后再次 acquire 返回 null");
    }
}
