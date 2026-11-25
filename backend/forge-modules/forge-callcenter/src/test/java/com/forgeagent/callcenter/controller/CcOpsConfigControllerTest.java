package com.forgeagent.callcenter.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.callcenter.domain.CsBlackPhone;
import com.forgeagent.callcenter.domain.CsDidRoute;
import com.forgeagent.callcenter.domain.CsNumberPool;
import com.forgeagent.callcenter.domain.CsOverflowConfig;
import com.forgeagent.callcenter.mapper.*;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.service.AdminWorkspaceScope;
import com.forgeagent.customerservice.service.CsWorkspaceResolver;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营配置管理端点契约测试:workspace 隔离与越权拒绝。
 */
@Tag("dev")
class CcOpsConfigControllerTest {

    private CsOverflowConfigMapper overflowMapper;
    private CsNumberPoolMapper numberMapper;
    private CsBlackPhoneMapper blackMapper;
    private CsDidRouteMapper didMapper;
    private CsEnterpriseMapper enterpriseMapper;
    private CsAgentStateLogMapper stateLogMapper;
    private CsCdrCompensationMapper cdrMapper;
    private CsWorkspaceResolver resolver;
    private AdminWorkspaceScope scope;
    private CcOpsConfigController controller;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsOverflowConfig.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsNumberPool.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsBlackPhone.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsDidRoute.class);

        overflowMapper = mock(CsOverflowConfigMapper.class);
        numberMapper = mock(CsNumberPoolMapper.class);
        blackMapper = mock(CsBlackPhoneMapper.class);
        didMapper = mock(CsDidRouteMapper.class);
        enterpriseMapper = mock(CsEnterpriseMapper.class);
        stateLogMapper = mock(CsAgentStateLogMapper.class);
        cdrMapper = mock(CsCdrCompensationMapper.class);
        resolver = mock(CsWorkspaceResolver.class);
        scope = mock(AdminWorkspaceScope.class);
        lenient().when(scope.resolveForList(any())).thenAnswer(inv -> {
            Object req = inv.getArgument(0);
            return req == null ? 1L : req;
        });
        lenient().when(scope.resolve(any())).thenAnswer(inv -> {
            Object req = inv.getArgument(0);
            return req == null ? 1L : req;
        });
        lenient().when(resolver.currentWorkspaceId()).thenReturn(1L);

        controller = new CcOpsConfigController(overflowMapper, numberMapper, blackMapper,
            didMapper, enterpriseMapper, stateLogMapper, cdrMapper, resolver, scope);
    }

    @Test
    void listScopedToCurrentWorkspace() {
        assertDoesNotThrow(() -> controller.listOverflow(null));
        assertDoesNotThrow(() -> controller.listNumbers(null));
        assertDoesNotThrow(() -> controller.listBlacklist(null));
        assertDoesNotThrow(() -> controller.listDidRoutes(null));
        assertDoesNotThrow(() -> controller.stateLogs(null));
        assertDoesNotThrow(() -> controller.cdrCompensations(null));
        verify(scope, org.mockito.Mockito.atLeastOnce()).resolveForList(null);
    }

    @Test
    void crossWorkspaceEditRejected() {
        // 编辑别空间的溢出策略:id=99 归属 ws=2,当前 ws=1 → 必须拒绝
        CsOverflowConfig foreign = new CsOverflowConfig();
        foreign.setId(99L);
        foreign.setWorkspaceId(2L);
        when(overflowMapper.selectById(99L)).thenReturn(foreign);

        var req = new CcOpsConfigController.SaveReq(99L, "改", "QUEUE_SIZE", 5,
            "MARK", null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(ServiceException.class, () -> controller.saveOverflow(req));
        verify(overflowMapper, never()).updateById(any(CsOverflowConfig.class));
    }

    @Test
    void crossWorkspaceDeleteRejected() {
        CsDidRoute foreign = new CsDidRoute();
        foreign.setId(88L);
        foreign.setWorkspaceId(2L);
        when(didMapper.selectById(88L)).thenReturn(foreign);
        assertThrows(ServiceException.class, () -> controller.deleteDidRoute(88L, null));
        verify(didMapper, never()).deleteById(any());
    }

    @Test
    void ownWorkspaceDeleteAllowed() {
        CsBlackPhone own = new CsBlackPhone();
        own.setId(77L);
        own.setWorkspaceId(1L);
        when(blackMapper.selectById(77L)).thenReturn(own);
        when(blackMapper.deleteById(77L)).thenReturn(1);
        assertDoesNotThrow(() -> controller.deleteBlacklist(77L, null));
        verify(blackMapper).deleteById(77L);
    }
    @Test
    void superAdminCanOperateSelectedWorkspace() {
        // 超管选空间 2:编辑归属 ws=2 的记录应通过(scope mock 按 requested 返回)
        when(scope.resolve(2L)).thenReturn(2L);
        CsOverflowConfig row = new CsOverflowConfig();
        row.setId(55L);
        row.setWorkspaceId(2L);
        when(overflowMapper.selectById(55L)).thenReturn(row);

        var req = new CcOpsConfigController.SaveReq(55L, "超管改", "QUEUE_SIZE", 5,
            "MARK", null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, 2L);
        assertDoesNotThrow(() -> controller.saveOverflow(req));
        verify(overflowMapper).updateById(any(CsOverflowConfig.class));
    }

    @Test
    void superAdminWithoutWorkspaceCannotWrite() {
        // 超管未选空间:scope.resolve(null)=null → 写操作拒绝
        when(scope.resolve(null)).thenReturn(null);
        var req = new CcOpsConfigController.SaveReq(null, "x", "QUEUE_SIZE", 5,
            "MARK", null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(ServiceException.class, () -> controller.saveOverflow(req));
        verify(overflowMapper, never()).insert(any(CsOverflowConfig.class));
    }

}
