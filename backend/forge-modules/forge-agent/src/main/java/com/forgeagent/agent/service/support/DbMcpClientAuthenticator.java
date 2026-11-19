package com.forgeagent.agent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agent.domain.AgentMcpClient;
import com.forgeagent.agent.mapper.AgentMcpClientMapper;
import com.forgeagent.agent.service.impl.AgentMcpClientServiceImpl;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.mcp.McpCaller;
import com.forgeagent.rag.mcp.McpClientAuthenticator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 对外 MCP 令牌解析实现(08方案 / A5 增强)。
 *
 * <p>实现了 forge-rag 侧的 {@link McpClientAuthenticator}:rag 不认识 agent 的客户端表,
 * 通过 SPI 拿到「令牌 → 调用方身份」。查库走 token_hash 唯一索引,结果缓存 30 秒,
 * 因此停用/过期/轮转最多 30 秒后生效——这是吞吐与实时性的取舍,写进了文档。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbMcpClientAuthenticator implements McpClientAuthenticator {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final AgentMcpClientMapper clientMapper;

    /** 负结果也缓存:防止随机令牌打库 */
    private final Cache<String, Optional<McpCaller>> cache = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL)
        .maximumSize(1024)
        .build();

    @Override
    public Optional<McpCaller> authenticate(String token) {
        if (!managed(token)) {
            return Optional.empty();
        }
        String hash = AgentMcpClientServiceImpl.sha256Hex(token);
        return cache.get(hash, this::load);
    }

    @Override
    public boolean managed(String token) {
        // 只认平台客户端令牌格式;其它交给旧单 token 回落
        return StringUtils.isNotBlank(token) && token.startsWith(AgentMcpClientServiceImpl.TOKEN_PREFIX);
    }

    private Optional<McpCaller> load(String tokenHash) {
        AgentMcpClient client = clientMapper.selectOne(new LambdaQueryWrapper<AgentMcpClient>()
            .eq(AgentMcpClient::getTokenHash, tokenHash)
            .last("limit 1"));
        if (client == null) {
            return Optional.empty();
        }
        if (!"0".equals(client.getEnabled())) {
            return Optional.empty();
        }
        if (client.getExpireTime() != null && client.getExpireTime().isBefore(LocalDateTime.now())) {
            log.info("[McpClient] 令牌已过期,拒绝访问: {}", client.getName());
            return Optional.empty();
        }
        touchLastUsed(client.getId());
        return Optional.of(new McpCaller(client.getName(), client.getWorkspaceId(), parseScopes(client.getScopes())));
    }

    /**
     * 记录最近调用时间。缓存命中期间不会再打库,因此写频率与 TTL 一致。
     * 失败不影响鉴权结果(审计字段,不是准入条件)。
     */
    private void touchLastUsed(Long id) {
        try {
            clientMapper.update(null, new LambdaUpdateWrapper<AgentMcpClient>()
                .eq(AgentMcpClient::getId, id)
                .set(AgentMcpClient::getLastUsedTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.debug("[McpClient] last_used_time 更新失败: {}", e.getMessage());
        }
    }

    private static Set<String> parseScopes(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(s -> !s.isEmpty())
            .forEach(scopes::add);
        return scopes;
    }
}
