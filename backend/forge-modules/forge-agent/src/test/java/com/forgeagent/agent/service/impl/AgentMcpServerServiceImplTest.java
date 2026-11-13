package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forgeagent.agent.domain.AgentMcpServer;
import com.forgeagent.agent.domain.bo.AgentMcpServerBo;
import com.forgeagent.agent.domain.vo.AgentMcpServerVo;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 服务器资源域双模式单测。
 *
 * <p>验证四个可见性缺口是否真的在服务层堵上:列表下推、详情读校验、写校验、
 * 连接测试读校验,以及创建归属解析与名称按归属范围隔离。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentMcpServerServiceImplTest {

    @Mock
    private AgentMcpServerMapper serverMapper;
    @Mock
    private ResourceAccessGuard resourceGuard;

    private AgentMcpServerServiceImpl service;

    @BeforeAll
    static void warmUpLambdaCache() {
        // 纯 Mockito 无 Spring 上下文时 MP 的 lambda 列名解析需要预热
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMcpServer.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentMcpServerServiceImpl(serverMapper, resourceGuard);
    }

    private static AgentMcpServer server(Long id, Long workspaceId) {
        AgentMcpServer s = new AgentMcpServer();
        s.setId(id);
        s.setWorkspaceId(workspaceId);
        s.setName("mcp-" + id);
        s.setServerType("streamable_http");
        s.setUrl("https://mcp.example.com/mcp");
        s.setStatus("0");
        return s;
    }

    private static AgentMcpServerBo bo() {
        AgentMcpServerBo bo = new AgentMcpServerBo();
        bo.setName("demo-mcp");
        bo.setServerType("streamable_http");
        bo.setUrl("https://mcp.example.com/mcp");
        bo.setWorkspaceId(900L);
        bo.setShared(false);
        return bo;
    }

    @Test
    void queryPageListPushesVisibilityIntoSql() {
        Page<AgentMcpServerVo> empty = new Page<>(1, 10);
        when(serverMapper.selectVoPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(empty);

        PageResult<AgentMcpServerVo> result = service.queryPageList(new AgentMcpServerBo(), new PageQuery());

        assertNotNull(result);
        verify(resourceGuard, times(1)).applyVisible(any(), any());
    }

    @Test
    void queryByIdRejectsOtherWorkspacePrivateServer() {
        when(serverMapper.selectById(11L)).thenReturn(server(11L, 200L));
        doThrow(new ServiceException("资源不存在或无权访问")).when(resourceGuard).assertReadable(200L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.queryById(11L));

        assertTrue(ex.getMessage().contains("无权访问"));
    }

    @Test
    void changeStatusChecksWritePermission() {
        when(serverMapper.selectById(11L)).thenReturn(server(11L, 200L));
        when(serverMapper.updateById(any(AgentMcpServer.class))).thenReturn(1);

        assertTrue(service.changeStatus(11L, "1"));

        verify(resourceGuard).assertWritable(200L);
    }

    @Test
    void changeStatusRejectsOtherWorkspaceServerWithoutUpdating() {
        when(serverMapper.selectById(11L)).thenReturn(server(11L, 200L));
        doThrow(new ServiceException("资源不存在或无权访问")).when(resourceGuard).assertWritable(200L);

        assertThrows(ServiceException.class, () -> service.changeStatus(11L, "1"));

        verify(serverMapper, never()).updateById(any(AgentMcpServer.class));
    }

    @Test
    void deleteByIdsChecksEveryRecord() {
        when(serverMapper.selectById(11L)).thenReturn(server(11L, 200L));
        when(serverMapper.selectById(12L)).thenReturn(server(12L, 300L));
        when(serverMapper.deleteByIds(List.of(11L, 12L))).thenReturn(2);

        assertTrue(service.deleteByIds(List.of(11L, 12L)));

        verify(resourceGuard).assertWritable(200L);
        verify(resourceGuard).assertWritable(300L);
    }

    @Test
    void testConnectionRejectsInvisibleServerBeforeDialingOut() {
        when(serverMapper.selectById(11L)).thenReturn(server(11L, 200L));
        doThrow(new ServiceException("资源不存在或无权访问")).when(resourceGuard).assertReadable(200L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.testConnection(11L));

        assertTrue(ex.getMessage().contains("无权访问"), "必须在建立连接之前被拦下");
        verify(serverMapper, never()).updateById(any(AgentMcpServer.class));
    }

    @Test
    void normalUserCannotCreatePlatformSharedServer() {
        AgentMcpServerBo bo = bo();
        bo.setWorkspaceId(null);
        bo.setShared(true);
        when(resourceGuard.resolveCreateWorkspace(isNull(), eq(true)))
            .thenThrow(new ServiceException("仅超级管理员可创建平台共享资源"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));

        assertTrue(ex.getMessage().contains("超级管理员"));
        verify(serverMapper, never()).insert(any(AgentMcpServer.class));
    }

    @Test
    void nameUniquenessIsScopedToResolvedWorkspace() {
        when(resourceGuard.resolveCreateWorkspace(900L, false)).thenReturn(900L);
        when(serverMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo()));

        assertTrue(ex.getMessage().contains("名称已存在"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AgentMcpServer>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(serverMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("workspace_id"),
            "名称查重必须带归属空间条件,否则共享资源会挡住私有资源同名");
    }

    @Test
    void nameUniquenessOnSharedResourceUsesIsNull() {
        AgentMcpServerBo bo = bo();
        bo.setWorkspaceId(null);
        bo.setShared(true);
        when(resourceGuard.resolveCreateWorkspace(isNull(), eq(true))).thenReturn(null);
        when(serverMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(ServiceException.class, () -> service.insertByBo(bo));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AgentMcpServer>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(serverMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("IS NULL"),
            "平台共享资源的名称查重须限定 workspace_id IS NULL");
    }

    @Test
    void nonHttpUrlIsRejectedBeforeAnyInsert() {
        AgentMcpServerBo bo = bo();
        bo.setUrl("file:///etc/passwd");
        when(resourceGuard.resolveCreateWorkspace(900L, false)).thenReturn(900L);
        when(serverMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));

        assertTrue(ex.getMessage().contains("http/https"));
        verify(serverMapper, never()).insert(any(AgentMcpServer.class));
    }

    @Test
    void updateRejectsOtherWorkspaceServerBeforeAnyChange() {
        AgentMcpServerBo bo = bo();
        bo.setId(11L);
        when(serverMapper.selectById(11L)).thenReturn(server(11L, 200L));
        doThrow(new ServiceException("资源不存在或无权访问")).when(resourceGuard).assertWritable(200L);

        assertThrows(ServiceException.class, () -> service.updateByBo(bo));

        verify(serverMapper, never()).updateById(any(AgentMcpServer.class));
    }
}
