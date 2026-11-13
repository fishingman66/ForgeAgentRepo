package com.forgeagent.agent.service.impl;

import com.forgeagent.agent.domain.AgentMcpServer;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * MCP 服务器空间归属:Agent 绑定时的可见性校验(与工具/模型同口径)。
 *
 * <p>{@code validateBindings} 是私有方法,这里用反射直接调用以锁定「只查存在 → 补归属校验」
 * 这次改动的行为,避免为测试改动生产代码结构。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentInfoServiceMcpBindingTest {

    @Mock
    private AgentMcpServerMapper mcpServerMapper;
    @Mock
    private ResourceAccessGuard resourceGuard;
    @InjectMocks
    private AgentInfoServiceImpl service;

    private static AgentMcpServer server(Long workspaceId) {
        AgentMcpServer s = new AgentMcpServer();
        s.setId(11L);
        s.setName("demo-mcp");
        s.setWorkspaceId(workspaceId);
        return s;
    }

    private static Throwable invokeValidate(AgentInfoServiceImpl target, List<Long> mcpIds, Long workspaceId) {
        try {
            Method m = AgentInfoServiceImpl.class.getDeclaredMethod(
                "validateBindings", Long.class, List.class, List.class, List.class, Long.class);
            m.setAccessible(true);
            m.invoke(target, new Object[]{null, null, null, mcpIds, workspaceId});
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void platformSharedServerCanBeBoundByAnyWorkspace() {
        when(mcpServerMapper.selectByIds(List.of(11L))).thenReturn(List.of(server(null)));

        assertNull(invokeValidate(service, List.of(11L), 900L));
    }

    @Test
    void otherWorkspacePrivateServerCannotBeBound() {
        when(mcpServerMapper.selectByIds(List.of(11L))).thenReturn(List.of(server(200L)));
        doThrow(new ServiceException("资源不存在或无权访问"))
            .when(resourceGuard).assertReadableBy(200L, 900L);

        Throwable ex = invokeValidate(service, List.of(11L), 900L);

        assertInstanceOf(ServiceException.class, ex);
        assertTrue(ex.getMessage().contains("无权访问"));
    }

    @Test
    void missingServerIsRejectedBeforeVisibilityCheck() {
        when(mcpServerMapper.selectByIds(List.of(11L))).thenReturn(List.of());

        Throwable ex = invokeValidate(service, List.of(11L), 900L);

        assertInstanceOf(ServiceException.class, ex);
        assertTrue(ex.getMessage().contains("不存在"));
    }
}
