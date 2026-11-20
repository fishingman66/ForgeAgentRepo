package com.forgeagent.agent.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * F4一期:审批单重放前置校验单测。
 *
 * <p>验证:无快照/无 toolId 退化、Agent 缺失退化、B/C 档工具拒绝重放、
 * 合法 A 档 ToolDef 快照能反序列化。真实工具执行走 workflow 原型,不在纯 Mockito 范围。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ApprovalReplayServiceTest {

    @Mock private ToolAssembler toolAssembler;
    @Mock private IAgentApprovalService approvalService;
    @Mock private AgentConfigCacheService agentConfigCache;
    @Mock private RunEventBuffer eventBuffer;

    private ApprovalReplayService service;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ApprovalReplayService(toolAssembler, approvalService, agentConfigCache, eventBuffer);
    }

    private static AgentApproval approval(String snapshot, Long toolId) {
        AgentApproval a = new AgentApproval();
        a.setId(1L);
        a.setAgentId(100L);
        a.setToolId(toolId);
        a.setToolDefSnapshot(snapshot);
        a.setCallArgs("{\"q\":\"test\"}");
        a.setToolName("http_tool");
        return a;
    }

    @Test
    void noSnapshotDegradesWithoutAttempt() {
        var r = service.replay(approval(null, 1L));

        assertFalse(r.attempted());
        assertTrue(r.message().contains("无工具定义快照"));
    }

    @Test
    void noToolIdDegradesWithoutAttempt() {
        var r = service.replay(approval("{}", null));

        assertFalse(r.attempted());
    }

    @Test
    void missingAgentDegradesWithoutAttempt() {
        when(agentConfigCache.getAgentById(100L)).thenReturn(null);

        var r = service.replay(approval("{}", 1L));

        assertFalse(r.attempted());
        assertTrue(r.message().contains("Agent"));
    }

    @Test
    void nonATierToolRejected() throws Exception {
        AgentInfo agent = new AgentInfo();
        agent.setWorkspaceId(200L);
        when(agentConfigCache.getAgentById(100L)).thenReturn(agent);
        String sqlDef = MAPPER.writeValueAsString(new ToolAssembler.ToolDef(
            1L, "sql_tool", "", "sql", 9L, null, null, null, null,
            null, null, null, null, "ask_async", 120));

        var r = service.replay(approval(sqlDef, 1L));

        assertFalse(r.attempted());
        assertTrue(r.message().contains("不在 A 档"));
    }

    @Test
    void httpToolDefSnapshotDeserializes() throws Exception {
        String httpDef = MAPPER.writeValueAsString(new ToolAssembler.ToolDef(
            1L, "http_tool", "", "http", null, "POST", "https://api.example.com/x", "{}", "{}",
            null, null, null, null, "ask_async", 120));
        ToolAssembler.ToolDef parsed = MAPPER.readValue(httpDef, ToolAssembler.ToolDef.class);

        assertEquals("http", parsed.toolType());
        assertEquals("http_tool", parsed.name());
        assertEquals("POST", parsed.httpMethod());
    }
}
