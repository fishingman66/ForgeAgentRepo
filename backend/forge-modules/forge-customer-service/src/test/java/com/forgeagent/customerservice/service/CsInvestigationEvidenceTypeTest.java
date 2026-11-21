package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsInvestigation;
import com.forgeagent.customerservice.domain.CsInvestigationEvidence;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsInvestigationEvidenceMapper;
import com.forgeagent.customerservice.mapper.CsInvestigationMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * B4:KB / TICKET_EVENT 结构化证据落地单测。直接调包级可见的证据追加方法,
 * 不跑异步 LLM 链路(那是真实网络依赖)。锁三件事:
 * ① KB 命中写 evidenceType=KB 且摘要脱敏;② TICKET_EVENT 取最近事件写 evidenceType=TICKET_EVENT;
 * ③ 数据源缺失/异常时不写假证据、不抛异常(不拖垮 SQL 证据与报告)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsInvestigationEvidenceTypeTest {

    @Mock private CsInvestigationMapper investigationMapper;
    @Mock private CsInvestigationEvidenceMapper evidenceMapper;
    @Mock private CsTicketMapper ticketMapper;
    @Mock private CsTicketService ticketService;
    @Mock private AgentDatasourceMapper datasourceMapper;
    @Mock private AgentConfigCacheService agentConfigCache;
    @Mock private SqlQueryService sqlQueryService;
    @Mock private CsChannelMapper channelMapper;
    @Mock private CsKnowledgeGateway knowledgeGateway;
    @Mock private com.forgeagent.agent.service.governance.PromptTemplateService promptTemplateService;
    @Mock private com.forgeagent.agent.api.customerservice.McpEvidenceGateway mcpEvidenceGateway;

    private CsInvestigationService service;

    @BeforeAll
    static void warmUp() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, CsChannel.class);
        TableInfoHelper.initTableInfo(a, CsInvestigation.class);
        TableInfoHelper.initTableInfo(a, CsInvestigationEvidence.class);
    }

    @BeforeEach
    void setUp() {
        service = new CsInvestigationService(investigationMapper, evidenceMapper, ticketMapper,
            ticketService, datasourceMapper, agentConfigCache, sqlQueryService,
            channelMapper, knowledgeGateway, promptTemplateService, mcpEvidenceGateway);
    }

    private static CsTicket ticket() {
        CsTicket t = new CsTicket();
        t.setId(11L); t.setWorkspaceId(7L); t.setChannelCode("web-main");
        t.setTitle("退款未到账"); t.setHandoffReason("客户催办");
        return t;
    }

    private static CsChannel channelWithKb() {
        CsChannel c = new CsChannel();
        c.setWorkspaceId(7L); c.setChannelCode("web-main"); c.setKnowledgeIds("101,102");
        return c;
    }

    private static KnowledgeSearchHitVo hit(String file, String content) {
        KnowledgeSearchHitVo h = new KnowledgeSearchHitVo();
        h.setFileName(file); h.setContent(content);
        return h;
    }

    @Test
    void kbHitWritesStructuredEvidence() {
        when(channelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(channelWithKb());
        when(knowledgeGateway.retrieve(eq(7L), anyList(), anyString())).thenReturn(
            new CsKnowledgeGateway.RetrievalOutcome(
                List.of(hit("退款流程.md", "在订单页申请退款")), Map.of()));

        StringBuilder text = new StringBuilder();
        int seq = service.appendKbEvidence(500L, ticket(), 1, text);

        assertEquals(2, seq, "命中应占用一个证据序号");
        ArgumentCaptor<CsInvestigationEvidence> cap = ArgumentCaptor.forClass(CsInvestigationEvidence.class);
        verify(evidenceMapper).insert(cap.capture());
        assertEquals("KB", cap.getValue().getEvidenceType());
        assertEquals(1, cap.getValue().getSuccess());
        assertTrue(cap.getValue().getQueryText().contains("退款流程.md"));
        assertTrue(cap.getValue().getResultDigest().contains("在订单页申请退款"));
        assertTrue(text.toString().contains("[KB]"));
    }

    @Test
    void kbWithoutChannelOrBindingsWritesNothing() {
        when(channelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        StringBuilder text = new StringBuilder();
        int seq = service.appendKbEvidence(500L, ticket(), 1, text);
        assertEquals(1, seq, "无渠道绑定不应占用证据序号");
        verifyNoInteractions(evidenceMapper);
    }

    @Test
    void ticketEventWritesStructuredEvidence() {
        CsTicketEvent e1 = new CsTicketEvent(); e1.setEventType("CREATED"); e1.setNote("客户进线");
        CsTicketEvent e2 = new CsTicketEvent(); e2.setEventType("HANDOFF"); e2.setNote("转人工");
        when(ticketService.events(11L)).thenReturn(List.of(e1, e2));

        StringBuilder text = new StringBuilder();
        int seq = service.appendTicketEventEvidence(500L, ticket(), 2, text);

        assertEquals(3, seq);
        ArgumentCaptor<CsInvestigationEvidence> cap = ArgumentCaptor.forClass(CsInvestigationEvidence.class);
        verify(evidenceMapper).insert(cap.capture());
        assertEquals("TICKET_EVENT", cap.getValue().getEvidenceType());
        assertEquals(2, cap.getValue().getRowCount());
        assertTrue(cap.getValue().getResultDigest().contains("HANDOFF"));
        assertTrue(text.toString().contains("[TICKET_EVENT]"));
    }

    @Test
    void mcpHypothesisFieldWritesStructuredEvidence() {
        CsTicket t = ticket();
        t.setAgentId(30L);
        java.util.Map<String, Object> mcp = new java.util.HashMap<>();
        mcp.put("server", "crm");
        mcp.put("tool", "query_order");
        mcp.put("args", java.util.Map.of("orderId", "A1"));
        java.util.Map<String, Object> hyp = new java.util.HashMap<>();
        hyp.put("title", "订单状态异常");
        hyp.put("mcp", mcp);
        when(mcpEvidenceGateway.call(eq(30L), eq(7L), eq("crm"), eq("query_order"),
            any(), eq(60))).thenReturn(
            new com.forgeagent.agent.api.customerservice.McpEvidenceGateway.McpCallResult(
                true, "订单已退款", null));

        StringBuilder text = new StringBuilder();
        int seq = service.appendMcpEvidence(500L, t, 2, text, hyp);

        assertEquals(3, seq);
        ArgumentCaptor<CsInvestigationEvidence> cap = ArgumentCaptor.forClass(CsInvestigationEvidence.class);
        verify(evidenceMapper).insert(cap.capture());
        assertEquals("MCP", cap.getValue().getEvidenceType());
        assertEquals(1, cap.getValue().getSuccess());
        assertEquals("crm/query_order", cap.getValue().getQueryText());
        assertTrue(cap.getValue().getResultDigest().contains("订单已退款"));
        assertTrue(text.toString().contains("[MCP]"));
    }

    @Test
    void mcpMissingOrBlankFieldWritesNothing() {
        CsTicket t = ticket();
        t.setAgentId(30L);
        java.util.Map<String, Object> hypNoMcp = new java.util.HashMap<>();
        hypNoMcp.put("title", "纯 SQL 假设");
        java.util.Map<String, Object> hypBlank = new java.util.HashMap<>();
        hypBlank.put("title", "空 server");
        hypBlank.put("mcp", java.util.Map.of("server", "", "tool", "t"));

        StringBuilder text = new StringBuilder();
        assertEquals(1, service.appendMcpEvidence(500L, t, 1, text, hypNoMcp));
        assertEquals(1, service.appendMcpEvidence(500L, t, 1, text, hypBlank));
        verifyNoInteractions(mcpEvidenceGateway, evidenceMapper);
    }

    @Test
    void mcpCallFailureWritesFailedEvidence() {
        CsTicket t = ticket();
        t.setAgentId(30L);
        java.util.Map<String, Object> hyp = new java.util.HashMap<>();
        hyp.put("title", "订单状态异常");
        hyp.put("mcp", java.util.Map.of("server", "crm", "tool", "query_order"));
        when(mcpEvidenceGateway.call(eq(30L), eq(7L), eq("crm"), eq("query_order"),
            any(), eq(60))).thenReturn(
            new com.forgeagent.agent.api.customerservice.McpEvidenceGateway.McpCallResult(
                false, null, "连接超时"));

        StringBuilder text = new StringBuilder();
        int seq = service.appendMcpEvidence(500L, t, 1, text, hyp);

        assertEquals(2, seq, "失败的 MCP 调用也占一个证据序号(失败证据进证据链)");
        ArgumentCaptor<CsInvestigationEvidence> cap = ArgumentCaptor.forClass(CsInvestigationEvidence.class);
        verify(evidenceMapper).insert(cap.capture());
        assertEquals("MCP", cap.getValue().getEvidenceType());
        assertEquals(0, cap.getValue().getSuccess());
        assertEquals("连接超时", cap.getValue().getErrorMessage());
    }

    @Test
    void evidenceSourceFailureDoesNotThrowOrWriteFakeEvidence() {
        when(channelMapper.selectOne(any(LambdaQueryWrapper.class))).thenThrow(new ServiceException("DB down"));
        when(ticketService.events(11L)).thenThrow(new ServiceException("DB down"));

        StringBuilder text = new StringBuilder();
        assertDoesNotThrow(() -> {
            assertEquals(1, service.appendKbEvidence(500L, ticket(), 1, text));
            assertEquals(1, service.appendTicketEventEvidence(500L, ticket(), 1, text));
        });
        verifyNoInteractions(evidenceMapper);
    }
}
