package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentMcpClient;
import com.forgeagent.agent.mapper.AgentMcpClientMapper;
import com.forgeagent.agent.service.impl.AgentMcpClientServiceImpl;
import com.forgeagent.rag.mcp.McpCaller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对外 MCP 令牌解析单测(08方案 / A5 增强)。
 *
 * <p>锁准入四态:有效放行并带出工作空间与授权域;停用/过期/非平台前缀一律拒绝。
 * 非 fgm_ 前缀的令牌**不得查库**——要留给旧单 token 回落分支。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DbMcpClientAuthenticatorTest {

    @Mock
    private AgentMcpClientMapper clientMapper;

    @BeforeAll
    static void warmUpLambdaCache() {
        // touchLastUsed 走 LambdaUpdateWrapper,需先初始化表信息
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentMcpClient.class);
    }

    private DbMcpClientAuthenticator authenticator() {
        return new DbMcpClientAuthenticator(clientMapper);
    }

    private static AgentMcpClient client(String enabled, LocalDateTime expire) {
        AgentMcpClient c = new AgentMcpClient();
        c.setId(7L);
        c.setName("DSH");
        c.setWorkspaceId(100L);
        c.setScopes("knowledge,skill");
        c.setEnabled(enabled);
        c.setExpireTime(expire);
        return c;
    }

    private String token() {
        return AgentMcpClientServiceImpl.generateToken();
    }

    @Test
    void validTokenYieldsCallerWithWorkspaceAndScopes() {
        String token = token();
        when(clientMapper.selectOne(any())).thenReturn(client("0", null));

        Optional<McpCaller> caller = authenticator().authenticate(token);

        assertTrue(caller.isPresent());
        assertEquals("DSH", caller.get().clientName());
        assertEquals(100L, caller.get().workspaceId());
        assertTrue(caller.get().allows(McpCaller.SCOPE_SKILL));
        assertTrue(caller.get().allows(McpCaller.SCOPE_KNOWLEDGE));
        // 命中后应刷 last_used_time
        verify(clientMapper).update(any(), any());
    }

    @Test
    void disabledClientIsRejected() {
        when(clientMapper.selectOne(any())).thenReturn(client("1", null));

        assertFalse(authenticator().authenticate(token()).isPresent());
        verify(clientMapper, never()).update(any(), any());
    }

    @Test
    void expiredClientIsRejected() {
        when(clientMapper.selectOne(any())).thenReturn(
            client("0", LocalDateTime.now().minusMinutes(1)));

        assertFalse(authenticator().authenticate(token()).isPresent());
    }

    @Test
    void unknownTokenIsRejected() {
        when(clientMapper.selectOne(any())).thenReturn(null);

        assertFalse(authenticator().authenticate(token()).isPresent());
    }

    @Test
    void foreignTokenNeverHitsDatabase() {
        // 旧单 token 可能是任意格式,不能拿它去打库
        assertFalse(authenticator().authenticate("legacy-plain-token").isPresent());
        assertFalse(authenticator().authenticate("").isPresent());
        assertFalse(authenticator().authenticate(null).isPresent());
        verify(clientMapper, never()).selectOne(any());
    }

    @Test
    void managedOnlyAcceptsPlatformTokenFormat() {
        DbMcpClientAuthenticator auth = authenticator();
        assertTrue(auth.managed(AgentMcpClientServiceImpl.generateToken()));
        assertFalse(auth.managed("legacy-plain-token"));
        assertFalse(auth.managed(""));
        assertFalse(auth.managed(null));
    }

    @Test
    void platformWideCallerIgnoresWorkspace() {
        McpCaller caller = McpCaller.platformWide("legacy-token");
        assertTrue(caller.canReadWorkspace(100L));
        assertTrue(caller.canReadWorkspace(null));
        assertTrue(caller.allows(McpCaller.SCOPE_KNOWLEDGE));
    }

    @Test
    void scopedCallerOnlySeesOwnOrSharedWorkspace() {
        McpCaller caller = new McpCaller("DSH", 100L, java.util.Set.of(McpCaller.SCOPE_SKILL));

        assertTrue(caller.canReadWorkspace(100L));
        assertTrue(caller.canReadWorkspace(null), "平台共享资源对所有调用方可见");
        assertFalse(caller.canReadWorkspace(200L));
        assertTrue(caller.allows(McpCaller.SCOPE_SKILL));
        assertFalse(caller.allows(McpCaller.SCOPE_KNOWLEDGE));
    }
}
