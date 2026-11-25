package com.forgeagent.customerservice.service;

import com.forgeagent.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 管理界面空间边界契约测试:普通用户忽略前端传参,超管可选空间。
 */
@Tag("dev")
class AdminWorkspaceScopeTest {

    private final CsWorkspaceResolver resolver = mock(CsWorkspaceResolver.class);
    private final com.forgeagent.customerservice.mapper.CsSeatMapper seatMapper =
        mock(com.forgeagent.customerservice.mapper.CsSeatMapper.class);
    private final AdminWorkspaceScope scope = new AdminWorkspaceScope(resolver, seatMapper);

    @Test
    void normalUserIgnoresRequestedWorkspace() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);
            when(resolver.currentWorkspaceId()).thenReturn(7L);
            // 写操作:普通用户传别空间 → 明确拒绝并提示
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.forgeagent.common.core.exception.ServiceException.class,
                () -> scope.resolve(999L));
            assertTrue(ex.getMessage().contains("只能操作本人坐席所属空间"), "给出明确提示");
            // 列表:传别空间仍返回自己空间(不打断浏览)
            assertEquals(7L, scope.resolveForList(999L), "列表越权参数被忽略,仍看自己空间");
            assertEquals(7L, scope.resolveForList(null), "列表同样只看自己空间");
            assertTrue(scope.availableWorkspaces().isEmpty(), "普通用户无候选空间");
        }
    }

    @Test
    void superAdminCanSelectWorkspaceOrAll() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            assertEquals(2L, scope.resolve(2L), "超管选空间 2");
            assertEquals(AdminWorkspaceScope.ALL_WORKSPACES, scope.resolve(null),
                "超管不传空间 = 全部(仅列表)");
            assertEquals(AdminWorkspaceScope.ALL_WORKSPACES, scope.resolveForList(null));
        }
    }
}
