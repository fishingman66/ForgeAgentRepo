package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentMcpServer;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 服务器空间归属:执行链路(对话装配)按归属空间过滤的表征测试。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ChatContextAssemblerMcpVisibilityTest {

    @Mock
    private AgentMcpServerMapper mcpServerMapper;
    @Mock
    private ResourceAccessGuard resourceGuard;
    @InjectMocks
    private ChatContextAssembler assembler;

    private static AgentMcpServer server(long id, Long workspaceId, String status) {
        AgentMcpServer s = new AgentMcpServer();
        s.setId(id);
        s.setName("mcp-" + id);
        s.setWorkspaceId(workspaceId);
        s.setServerType("streamable_http");
        s.setUrl("https://mcp.example.com/mcp");
        s.setStatus(status);
        return s;
    }

    @Test
    void loadMcpDefsKeepsOnlySharedAndOwnWorkspaceServers() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        when(mcpServerMapper.selectByIds(ids)).thenReturn(List.of(
            server(1L, null, "0"),    // 平台共享
            server(2L, 900L, "0"),  // 本空间私有
            server(3L, 200L, "0"),  // 其他空间私有
            server(4L, 400L, "1"))); // 已停用
        when(resourceGuard.readableBy(null, 900L)).thenReturn(true);
        when(resourceGuard.readableBy(900L, 900L)).thenReturn(true);
        when(resourceGuard.readableBy(200L, 900L)).thenReturn(false);

        List<ToolAssembler.McpDef> defs = assembler.loadMcpDefs(ids, 900L);

        assertEquals(2, defs.size());
        assertEquals(List.of(1L, 2L), defs.stream().map(ToolAssembler.McpDef::id).toList());
        verify(resourceGuard, never()).readableBy(400L, 900L); // 停用服务器不进入可见性判定
    }

    @Test
    void loadMcpDefsWithEmptyIdsSkipsQuery() {
        assertTrue(assembler.loadMcpDefs(List.of(), 900L).isEmpty());
        verify(mcpServerMapper, never()).selectByIds(List.of());
    }
}
