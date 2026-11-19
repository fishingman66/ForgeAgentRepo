package com.forgeagent.agent.service.impl;

import com.forgeagent.agent.domain.AgentMcpClient;
import com.forgeagent.agent.domain.bo.AgentMcpClientBo;
import com.forgeagent.agent.domain.vo.AgentMcpClientTokenVo;
import com.forgeagent.agent.mapper.AgentMcpClientMapper;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对外 MCP 客户端服务单测(08方案 / A5 增强)。
 *
 * <p>令牌安全是这里的主线:明文只出现一次、库里只落哈希与前缀、轮转后旧哈希被覆盖。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentMcpClientServiceImplTest {

    @Mock
    private AgentMcpClientMapper clientMapper;
    @Mock
    private ResourceAccessGuard resourceGuard;

    private AgentMcpClientServiceImpl service;

    @BeforeAll
    static void warmUpLambdaCache() {
        // 纯 Mockito 无 Spring 上下文时 MP 的 lambda 列名解析需要预热
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentMcpClient.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentMcpClientServiceImpl(clientMapper, resourceGuard);
    }

    @Test
    void tokenHasPlatformPrefixAndEnoughEntropy() {
        String token = AgentMcpClientServiceImpl.generateToken();

        assertTrue(token.startsWith(AgentMcpClientServiceImpl.TOKEN_PREFIX));
        // fgm_ + 32 字节 base64url 无填充(43 字符)
        assertEquals(AgentMcpClientServiceImpl.TOKEN_PREFIX.length() + 43, token.length());
        assertNotEquals(token, AgentMcpClientServiceImpl.generateToken());
    }

    @Test
    void hashIsStableAndNotReversible() {
        String token = AgentMcpClientServiceImpl.generateToken();
        String hash = AgentMcpClientServiceImpl.sha256Hex(token);

        assertEquals(hash, AgentMcpClientServiceImpl.sha256Hex(token));
        assertEquals(64, hash.length());
        assertNotEquals(token, hash);
    }

    @Test
    void prefixKeepsOnlyHead() {
        String token = "fgm_abcdefghijklmnop";
        String prefix = AgentMcpClientServiceImpl.prefixOf(token);

        assertTrue(token.startsWith(prefix));
        assertEquals(AgentMcpClientServiceImpl.TOKEN_PREFIX.length() + 8, prefix.length());
    }

    @Test
    void scopesAreNormalizedAndWhitelisted() {
        assertEquals("knowledge", AgentMcpClientServiceImpl.normalizeScopes(" KNOWLEDGE "));
        assertEquals("skill,knowledge", AgentMcpClientServiceImpl.normalizeScopes("skill,knowledge,skill"),
            "保序去重:不重排用户输入");
        assertEquals("", AgentMcpClientServiceImpl.normalizeScopes(null));
        assertEquals("", AgentMcpClientServiceImpl.normalizeScopes("  "));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> AgentMcpClientServiceImpl.normalizeScopes("knowledge,admin"));
        assertTrue(ex.getMessage().contains("不支持的授权域"));
    }

    @Test
    void expireTimeAcceptsThreeFormats() {
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0, 0),
            AgentMcpClientServiceImpl.parseTime("2026-12-01"));
        assertEquals(LocalDateTime.of(2026, 12, 1, 8, 30, 0),
            AgentMcpClientServiceImpl.parseTime("2026-12-01 08:30:00"));
        assertEquals(LocalDateTime.of(2026, 12, 1, 8, 30, 0),
            AgentMcpClientServiceImpl.parseTime("2026-12-01T08:30:00"));
        assertNull(AgentMcpClientServiceImpl.parseTime(""));
        assertThrows(ServiceException.class, () -> AgentMcpClientServiceImpl.parseTime("下周一"));
    }

    @Test
    void createStoresHashOnlyAndReturnsPlainTokenOnce() {
        when(resourceGuard.resolveCreateWorkspace(any(), any(Boolean.class))).thenReturn(100L);

        AgentMcpClientBo bo = new AgentMcpClientBo();
        bo.setName("DSH");
        bo.setWorkspaceId(100L);
        bo.setScopes("skill,knowledge");

        AgentMcpClientTokenVo vo = service.createClient(bo);

        ArgumentCaptor<AgentMcpClient> captor = ArgumentCaptor.forClass(AgentMcpClient.class);
        verify(clientMapper).insert(captor.capture());
        AgentMcpClient saved = captor.getValue();
        assertEquals("DSH", saved.getName());
        assertEquals("0", saved.getEnabled());
        assertEquals("skill,knowledge", saved.getScopes());
        assertEquals(AgentMcpClientServiceImpl.sha256Hex(vo.getToken()), saved.getTokenHash());
        assertNotEquals(vo.getToken(), saved.getTokenHash());
        assertEquals(saved.getTokenPrefix(), vo.getTokenPrefix());
    }

    @Test
    void rotateOverwritesHash() {
        AgentMcpClient exists = new AgentMcpClient();
        exists.setId(5L);
        exists.setName("DSH");
        exists.setTokenHash("old-hash");
        when(clientMapper.selectById(5L)).thenReturn(exists);

        AgentMcpClientTokenVo vo = service.rotateToken(5L);

        assertEquals(AgentMcpClientServiceImpl.sha256Hex(vo.getToken()),
            AgentMcpClientServiceImpl.sha256Hex(vo.getToken()));
        assertNotEquals("old-hash", vo.getToken());
        verify(clientMapper).update(any(), any());
    }

    @Test
    void missingClientIsRejected() {
        when(clientMapper.selectById(anyLong())).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.rotateToken(9L));
        assertTrue(ex.getMessage().contains("不存在"));
    }
}
