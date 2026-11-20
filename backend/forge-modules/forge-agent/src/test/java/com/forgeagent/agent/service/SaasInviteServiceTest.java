package com.forgeagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agent.domain.AgentWorkspaceInvite;
import com.forgeagent.agent.mapper.AgentWorkspaceInviteMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** F2 邀请令牌:明文只回传一次 / 角色白名单 / 过期与次数校验。 */
@Tag("dev")
class SaasInviteServiceTest {

    private AgentWorkspaceInviteMapper inviteMapper;
    private AgentWorkspaceMemberMapper memberMapper;
    private WorkspacePermissionHelper permissionHelper;
    private SaasInviteService service;

    @BeforeEach
    void setUp() {
        inviteMapper = mock(AgentWorkspaceInviteMapper.class);
        memberMapper = mock(AgentWorkspaceMemberMapper.class);
        permissionHelper = mock(WorkspacePermissionHelper.class);
        service = new SaasInviteService(inviteMapper, memberMapper, permissionHelper);
    }

    @Test
    void createReturnsTokenOnceAndStoresHashOnly() {
        SaasInviteService.InviteVo vo = service.create(7L, "developer", 2, 24);
        assertNotNull(vo.token());
        assertTrue(vo.token().startsWith("fgi_"), "token 应带可识别前缀");
        assertEquals("developer", vo.role());
        assertNotNull(vo.expiresAt());

        ArgumentCaptor<AgentWorkspaceInvite> cap = ArgumentCaptor.forClass(AgentWorkspaceInvite.class);
        verify(inviteMapper).insert(cap.capture());
        AgentWorkspaceInvite stored = cap.getValue();
        assertNotEquals(vo.token(), stored.getTokenHash(), "库里只存哈希,不存明文");
        assertEquals(64, stored.getTokenHash().length(), "SHA-256 十六进制 64 位");
        assertEquals(2, stored.getMaxUses());
        verify(permissionHelper).checkWsAdmin(7L);
    }

    @Test
    void createRejectsAdminRole() {
        assertThrows(ServiceException.class, () -> service.create(7L, "admin", 1, 24));
        verifyNoInteractions(inviteMapper);
    }

    @Test
    void acceptRejectsExpiredToken() {
        AgentWorkspaceInvite expired = new AgentWorkspaceInvite();
        expired.setId(1L);
        expired.setWorkspaceId(7L);
        expired.setTokenHash("x");
        expired.setExpiresAt(new Date(System.currentTimeMillis() - 1000));
        expired.setMaxUses(1);
        when(inviteMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(expired);

        assertThrows(ServiceException.class, () -> service.accept("fgi_any", 42L));
        verify(inviteMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptUsesConditionalUpdateToClaimSlot() {
        AgentWorkspaceInvite invite = new AgentWorkspaceInvite();
        invite.setId(2L);
        invite.setWorkspaceId(7L);
        invite.setExpiresAt(new Date(System.currentTimeMillis() + 3600_000));
        invite.setMaxUses(1);
        invite.setRole("runner");
        when(inviteMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(invite);
        when(inviteMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        Long ws = service.accept("fgi_valid", 42L);
        assertEquals(7L, ws);
        verify(inviteMapper).update(any(), any(LambdaUpdateWrapper.class));
        verify(memberMapper).insert(any(com.forgeagent.agentcore.domain.AgentWorkspaceMember.class));
    }

    @Test
    void acceptRejectsWhenSlotExhausted() {
        AgentWorkspaceInvite invite = new AgentWorkspaceInvite();
        invite.setId(3L);
        invite.setWorkspaceId(7L);
        invite.setExpiresAt(new Date(System.currentTimeMillis() + 3600_000));
        invite.setMaxUses(1);
        invite.setRole("runner");
        when(inviteMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(invite);
        when(inviteMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.accept("fgi_used", 42L));
        verify(memberMapper, never()).insert(any(com.forgeagent.agentcore.domain.AgentWorkspaceMember.class));
    }
}
