package com.forgeagent.rag.service.governance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.rag.domain.AgentKbDocAcl;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.mapper.AgentKbDocAclMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F2 知识库可见性组合契约:工作空间边界(workspace_member)× 文档治理(DocVisibilityResolver)两层串联。
 *
 * <p>锁定两件事:① workspace 校验走 agent_workspace_member,与 ResourceAccessGuard 同源,
 * F2 邀请写入成员后即时生效;② RESTRICTED 文档治理是正交层,受邀成员若无对应平台角色仍被隐藏,
 * 且超管无角色同样 fail-closed。避免两链路被误改成分叉或互相豁免。</p>
 */
@Tag("dev")
class KbWorkspaceVisibilityContractTest {

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AgentKnowledgeDocument.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AgentKbDocAcl.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AgentWorkspaceMember.class);
    }

    private AgentWorkspaceMemberMapper memberMapper;
    private AgentKnowledgeDocumentMapper documentMapper;
    private AgentKbDocAclMapper aclMapper;
    private WorkspacePermissionHelper permissionHelper;
    private DocVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        memberMapper = mock(AgentWorkspaceMemberMapper.class);
        documentMapper = mock(AgentKnowledgeDocumentMapper.class);
        aclMapper = mock(AgentKbDocAclMapper.class);
        permissionHelper = new WorkspacePermissionHelper(memberMapper);
        resolver = new DocVisibilityResolver(documentMapper, aclMapper);
    }

    private AgentKnowledgeDocument doc(long id, String visibility) {
        AgentKnowledgeDocument d = new AgentKnowledgeDocument();
        d.setId(id);
        d.setVisibility(visibility);
        d.setPublishStatus("PUBLISHED");
        d.setTrashed(0);
        d.setEffectiveAt(null);
        d.setExpiresAt(null);
        return d;
    }

    @Test
    void invitedMemberPassesWorkspaceBoundary() {
        // F2 邀请写入 workspace_member 后,count=1 → 放行
        when(memberMapper.selectCount(any())).thenReturn(1L);
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);
            login.when(LoginHelper::getUserId).thenReturn(42L);
            assertDoesNotThrow(() -> permissionHelper.checkWsMember(7L),
                "受邀成员应通过 workspace 边界校验");
        }
    }

    @Test
    void nonMemberBlockedBeforeResolverRuns() {
        when(memberMapper.selectCount(any())).thenReturn(0L);
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);
            login.when(LoginHelper::getUserId).thenReturn(99L);
            assertThrows(ServiceException.class, () -> permissionHelper.checkWsMember(7L),
                "非成员必须在检索前被拦截,而不是靠 resolver 兜底");
        }
        verifyNoInteractions(documentMapper);
    }

    @Test
    void invitedMemberStillBoundByRestrictedRoleAcl() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc(1, "RESTRICTED")));
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(42L);
            when(aclMapper.selectRoleIdsByUserId(42L)).thenReturn(List.of(3L));
            DocVisibilityResolver.DocScope scope = resolver.resolve(11L);
            assertTrue(scope.hiddenDocIds().contains(1L),
                "受邀成员无对应平台角色时 RESTRICTED 文档仍应隐藏——治理层是正交的");
        }
    }

    @Test
    void superAdminWithoutRoleStillFailClosedOnRestricted() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc(2, "RESTRICTED")));
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            when(aclMapper.selectRoleIdsByUserId(1L)).thenReturn(List.of());
            DocVisibilityResolver.DocScope scope = resolver.resolve(12L);
            assertTrue(scope.hiddenDocIds().contains(2L),
                "超管无角色时 RESTRICTED 仍隐藏——resolver 不看 isSuperAdmin,保持 fail-closed");
        }
    }

    @Test
    void inheritedDocVisibleToInvitedMember() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc(3, "INHERIT")));
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(42L);
            when(aclMapper.selectRoleIdsByUserId(42L)).thenReturn(List.of());
            DocVisibilityResolver.DocScope scope = resolver.resolve(13L);
            assertFalse(scope.hiddenDocIds().contains(3L), "INHERIT 文档对受邀成员可见");
        }
    }
}
