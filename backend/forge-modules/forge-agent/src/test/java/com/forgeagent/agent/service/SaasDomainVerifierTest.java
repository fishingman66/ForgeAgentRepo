package com.forgeagent.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * F2:域名校验服务单测。用 spy 覆写 resolve() 避免真连 DNS,锁四件事:
 * ① 空域名=NONE;② 非法格式=INVALID(不触发 DNS);③ 可解析/不可解析落对应状态;
 * ④ 校验结果写回 workspace 两列且带 workspace 条件。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaasDomainVerifierTest {

    @Mock private AgentWorkspaceMapper workspaceMapper;
    @Mock private WorkspacePermissionHelper permissionHelper;

    private SaasDomainVerifier verifier;

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentWorkspace.class);
    }

    private SaasDomainVerifier verifierWith(String domain, boolean resolvable) {
        AgentWorkspace ws = new AgentWorkspace();
        ws.setId(7L);
        ws.setHelpCenterDomain(domain);
        when(workspaceMapper.selectById(7L)).thenReturn(ws);
        when(workspaceMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        SaasDomainVerifier v = spy(new SaasDomainVerifier(workspaceMapper, permissionHelper));
        doReturn(resolvable).when(v).resolve(anyString());
        return v;
    }

    private void assertStatusWritten(String expected) {
        ArgumentCaptor<LambdaUpdateWrapper<AgentWorkspace>> cap =
            ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(workspaceMapper).update(any(), cap.capture());
        String where = cap.getValue().getSqlSegment();
        assertTrue(where.contains("id"), where);
        String set = cap.getValue().getSqlSet();
        assertTrue(set.contains("domain_status"), set);
        assertTrue(set.contains("domain_verified_at"), set);
        assertTrue(cap.getValue().getParamNameValuePairs().values().stream()
            .anyMatch(expected::equals), "应写入状态 " + expected);
    }

    @Test
    void blankDomainReturnsNone() {
        verifier = verifierWith("  ", true);
        assertEquals(SaasDomainVerifier.STATUS_NONE, verifier.verify(7L));
        verify(verifier, never()).resolve(anyString());
        assertStatusWritten(SaasDomainVerifier.STATUS_NONE);
        verify(permissionHelper).checkWsAdmin(7L);
    }

    @Test
    void invalidFormatReturnsInvalidWithoutDns() {
        verifier = verifierWith("https://help.example.com/path", true);
        assertEquals(SaasDomainVerifier.STATUS_INVALID, verifier.verify(7L));
        verify(verifier, never()).resolve(anyString());
        assertStatusWritten(SaasDomainVerifier.STATUS_INVALID);
    }

    @Test
    void resolvableDomainWritesResolvable() {
        verifier = verifierWith("help.example.com", true);
        assertEquals(SaasDomainVerifier.STATUS_RESOLVABLE, verifier.verify(7L));
        verify(verifier).resolve("help.example.com");
        assertStatusWritten(SaasDomainVerifier.STATUS_RESOLVABLE);
    }

    @Test
    void unresolvableDomainWritesUnresolvable() {
        verifier = verifierWith("no-such-host.example", false);
        assertEquals(SaasDomainVerifier.STATUS_UNRESOLVABLE, verifier.verify(7L));
        assertStatusWritten(SaasDomainVerifier.STATUS_UNRESOLVABLE);
    }

    @Test
    void missingWorkspaceRejected() {
        verifier = spy(new SaasDomainVerifier(workspaceMapper, permissionHelper));
        when(workspaceMapper.selectById(7L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> verifier.verify(7L));
        verify(permissionHelper).checkWsAdmin(7L);
    }
}
