package com.forgeagent.rag.service.governance;

import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.rag.domain.AgentKbDocAcl;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.mapper.AgentKbDocAclMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R5-1/R5-2 可见性解析单测:治理谓词、ACL 角色求交、超限降级
 *
 * @author ForgeAgent
 */
@Tag("dev")
class DocVisibilityResolverTest {

    @BeforeAll
    static void warmUp() {
        // 纯 Mockito 无 Spring:MP lambda 列名解析需要预热 TableInfo
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            com.forgeagent.rag.domain.AgentKnowledgeDocument.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            com.forgeagent.rag.domain.AgentKbDocAcl.class);
    }

    private final AgentKnowledgeDocumentMapper documentMapper = mock(AgentKnowledgeDocumentMapper.class);
    private final AgentKbDocAclMapper aclMapper = mock(AgentKbDocAclMapper.class);
    private final DocVisibilityResolver resolver = new DocVisibilityResolver(documentMapper, aclMapper);

    private AgentKnowledgeDocument doc(long id, String visibility, String publishStatus,
                                       Integer trashed, Date effectiveAt, Date expiresAt) {
        AgentKnowledgeDocument d = new AgentKnowledgeDocument();
        d.setId(id);
        d.setVisibility(visibility);
        d.setPublishStatus(publishStatus);
        d.setTrashed(trashed);
        d.setEffectiveAt(effectiveAt);
        d.setExpiresAt(expiresAt);
        return d;
    }

    @Test
    void governancePredicateHidesUnpublishedExpiredAndTrashed() {
        long future = System.currentTimeMillis() + 86_400_000L;
        long past = System.currentTimeMillis() - 86_400_000L;
        when(documentMapper.selectList(any())).thenReturn(List.of(
            doc(1, "INHERIT", "PUBLISHED", 0, null, null),
            doc(2, "INHERIT", "PENDING_REVIEW", 0, null, null),
            doc(3, "INHERIT", "PUBLISHED", 0, new Date(future), null),
            doc(4, "INHERIT", "PUBLISHED", 0, null, new Date(past)),
            doc(5, "INHERIT", "PUBLISHED", 1, null, null)));
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            when(aclMapper.selectRoleIdsByUserId(anyLong())).thenReturn(List.of(9L));
            DocVisibilityResolver.DocScope scope = resolver.resolve(10L);
            assertEquals(List.of(2L, 3L, 4L, 5L), scope.hiddenDocIds());
            assertNull(scope.visibleDocIds(), "hidden 不多时按 not-in 即可");
        }
    }

    @Test
    void restrictedDocHiddenWithoutRoleIntersection() {
        when(documentMapper.selectList(any())).thenReturn(List.of(
            doc(1, "INHERIT", "PUBLISHED", 0, null, null),
            doc(2, "RESTRICTED", "PUBLISHED", 0, null, null),
            doc(3, "RESTRICTED", "PUBLISHED", 0, null, null)));
        AgentKbDocAcl acl2 = new AgentKbDocAcl();
        acl2.setDocumentId(2L);
        acl2.setRoleId(100L);
        AgentKbDocAcl acl3 = new AgentKbDocAcl();
        acl3.setDocumentId(3L);
        acl3.setRoleId(200L);
        when(aclMapper.selectList(any())).thenReturn(List.of(acl2, acl3));
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(7L);
            when(aclMapper.selectRoleIdsByUserId(7L)).thenReturn(List.of(100L));
            DocVisibilityResolver.DocScope scope = resolver.resolve(10L);
            // 文档2 角色命中可见;文档3 未授权被隐藏
            assertEquals(List.of(3L), scope.hiddenDocIds());
        }
    }

    @Test
    void unauthenticatedHidesAllRestricted() {
        when(documentMapper.selectList(any())).thenReturn(List.of(
            doc(1, "INHERIT", "PUBLISHED", 0, null, null),
            doc(2, "RESTRICTED", "PUBLISHED", 0, null, null)));
        when(aclMapper.selectList(any())).thenReturn(List.of());
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenThrow(new IllegalStateException("not login"));
            DocVisibilityResolver.DocScope scope = resolver.resolve(10L);
            assertEquals(List.of(2L), scope.hiddenDocIds(), "未登录时 RESTRICTED 默认拒绝");
        }
    }

    @Test
    void overflowSwitchesToAllowListThenDegrades() {
        List<AgentKnowledgeDocument> docs = new ArrayList<>();
        for (long i = 1; i <= 600; i++) {
            docs.add(doc(i, "INHERIT", "PUBLISHED", 0, null, null));
        }
        // 600 篇全未发布 → hidden=600 > 500,给允许列表(0 篇可见)
        for (int i = 0; i < docs.size(); i++) {
            docs.get(i).setPublishStatus("DRAFT");
        }
        when(documentMapper.selectList(any())).thenReturn(docs);
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            when(aclMapper.selectRoleIdsByUserId(anyLong())).thenReturn(List.of());
            DocVisibilityResolver.DocScope scope = resolver.resolve(10L);
            assertEquals(600, scope.hiddenDocIds().size());
            assertTrue(scope.visibleDocIds() != null && scope.visibleDocIds().isEmpty());
            assertTrue(!scope.vectorFilterOverflow(), "允许列表为空不算溢出");
        }
    }

    @Test
    void emptyKbReturnsEmptyScope() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        DocVisibilityResolver.DocScope scope = resolver.resolve(10L);
        assertTrue(scope.isEmpty());
        assertNull(scope.visibleDocIds());
    }
}
