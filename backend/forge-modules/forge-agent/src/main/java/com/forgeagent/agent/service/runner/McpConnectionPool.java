package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A4 / docs-08:MCP 连接池
 *
 * <p>按「服务器指纹」跨请求复用 AgentScope {@code McpClientWrapper},消除每次请求
 * 重建连接 + initialize(上限 30s) 的开销。指纹 = serverType|url|超时|排序后 headers,
 * 任一配置变更自然产生新指纹,旧连接靠空闲过期回收。</p>
 *
 * <p>健康策略:命中后仍调一次 listTools 校验连接并刷新工具列表(单次 RPC,远比建连
 * 便宜,且能感知服务端工具变更);校验失败即驱逐关闭,由 ToolAssembler 走新建路径自愈。
 * 被替换/过期的连接在 removalListener 中关闭,装配阶段绝不能把池内连接交给
 * {@code Assembly.closables},否则请求结束会把池内连接关掉。</p>
 *
 * <p>开关:{@code agent.chat.mcp-pool-enabled}(默认开启),异常时置 false 一键回退
 * 到「每请求独立连接、请求结束关闭」的原行为。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class McpConnectionPool {

    /** 池内条目:连接 + 最近一次校验的工具列表 */
    public record Pooled(io.agentscope.core.tool.mcp.McpClientWrapper wrapper, List<McpSchema.Tool> tools)
        implements AutoCloseable {

        @Override
        public void close() {
            try {
                wrapper.close();
            } catch (Exception ignore) {
                // 连接关闭失败无需处理
            }
        }
    }

    private final Cache<String, Pooled> cache;
    /** 纯 Jackson 3,不用 JsonUtils(其静态初始化依赖 Spring 容器,单测环境不可用) */
    private final JsonMapper headerMapper = JsonMapper.builder().build();

    public McpConnectionPool(AgentChatProperties chatProperties) {
        int idleSeconds = positive(chatProperties.getMcpPoolIdleSeconds(), 300);
        int maxSize = positive(chatProperties.getMcpPoolMaxSize(), 32);
        this.cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(idleSeconds))
            .maximumSize(maxSize)
            .removalListener((String key, Pooled value, RemovalCause cause) -> {
                if (value == null) {
                    return;
                }
                value.close();
                log.info("[McpPool] 连接关闭(指纹={} 原因={})", shortKey(key), cause);
            })
            .build();
    }

    /**
     * 服务器指纹:serverType|url|timeout|排序后 headers(headers JSON 键序不稳定,先排序)
     */
    public String fingerprint(ToolAssembler.McpDef def) {
        StringBuilder sb = new StringBuilder()
            .append(def.serverType() == null ? "streamable_http" : def.serverType())
            .append('|').append(def.url())
            .append('|').append(def.timeoutSeconds() == null ? 30 : def.timeoutSeconds())
            .append('|');
        Map<String, Object> headers = parseHeaders(def.headers());
        for (Map.Entry<String, Object> entry : new TreeMap<>(headers).entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
        }
        return sb.toString();
    }

    /**
     * 取池内连接并做 listTools 健康校验(同时刷新工具列表)。
     * 连接失效、服务端无工具或校验超时:驱逐并返回 null,由调用方走新建路径自愈。
     */
    public Pooled acquire(String fingerprint) {
        Pooled pooled = cache.getIfPresent(fingerprint);
        if (pooled == null) {
            return null;
        }
        try {
            List<McpSchema.Tool> tools = pooled.wrapper()
                .listTools().block(Duration.ofSeconds(30));
            if (tools == null || tools.isEmpty()) {
                throw new IllegalStateException("未发现工具");
            }
            log.debug("[McpPool] 命中连接(指纹={} 工具数={})", shortKey(fingerprint), tools.size());
            return new Pooled(pooled.wrapper(), tools);
        } catch (Exception e) {
            cache.invalidate(fingerprint);
            log.warn("[McpPool] 池内连接校验失败,驱逐重建(指纹={} 原因={})",
                shortKey(fingerprint), rootMessage(e));
            return null;
        }
    }

    /**
     * 新建成功的连接放入池中。同指纹并发建连时后写覆盖前写,被覆盖的旧连接由
     * removalListener 关闭(装配阶段与工具调用阶段时间窗错开,风险可接受)。
     */
    public void offer(String fingerprint, Pooled pooled) {
        if (fingerprint == null || pooled == null
            || pooled.tools() == null || pooled.tools().isEmpty()) {
            return;
        }
        cache.put(fingerprint, pooled);
    }

    private Map<String, Object> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = headerMapper.readValue(headersJson, new TypeReference<>() {
            });
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            // 与 buildMcpWrapper 的容错口径一致:坏 headers 在建连时自然报错
            return Map.of();
        }
    }

    private static String shortKey(String fingerprint) {
        return fingerprint.length() <= 48 ? fingerprint : fingerprint.substring(0, 48) + "...";
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
