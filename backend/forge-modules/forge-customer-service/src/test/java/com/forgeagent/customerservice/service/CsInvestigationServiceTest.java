package com.forgeagent.customerservice.service;

import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.domain.CsInvestigation;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.bo.CsInvestigationConfirmBo;
import com.forgeagent.customerservice.mapper.CsInvestigationEvidenceMapper;
import com.forgeagent.customerservice.mapper.CsInvestigationMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4 RCA 安全边界契约:跨空间/无白名单数据源/未完成调查一律拒绝。
 *
 * <p>只测"闸门"不测 LLM 链路(模型调用在异步分支里,依赖真实网络)。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsInvestigationServiceTest {

    @Mock
    private CsInvestigationMapper investigationMapper;
    @Mock
    private CsInvestigationEvidenceMapper evidenceMapper;
    @Mock
    private CsTicketMapper ticketMapper;
    @Mock
    private CsTicketService ticketService;
    @Mock
    private AgentDatasourceMapper datasourceMapper;
    @Mock
    private AgentConfigCacheService agentConfigCache;
    @Mock
    private SqlQueryService sqlQueryService;

    private CsInvestigationService service;

    @BeforeEach
    void setUp() {
        service = new CsInvestigationService(investigationMapper, evidenceMapper, ticketMapper,
            ticketService, datasourceMapper, agentConfigCache, sqlQueryService);
    }

    private CsTicket ticket(long workspaceId) {
        CsTicket ticket = new CsTicket();
        ticket.setId(11L);
        ticket.setWorkspaceId(workspaceId);
        ticket.setTicketNo("T-001");
        ticket.setStatus("PROCESSING");
        ticket.setAgentId(1001L);
        return ticket;
    }

    private AgentDatasource datasource(Long workspaceId, String whitelist) {
        AgentDatasource ds = new AgentDatasource();
        ds.setId(7L);
        ds.setWorkspaceId(workspaceId);
        ds.setTableWhitelist(whitelist);
        return ds;
    }

    @Test
    void rejectsTicketFromOtherWorkspace() {
        when(ticketMapper.selectById(11L)).thenReturn(ticket(900L));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.start(11L, 7L, 800L, 5L));

        assertTrue(ex.getMessage().contains("工单不存在"));
        verify(investigationMapper, never()).insert(any(CsInvestigation.class));
    }

    @Test
    void rejectsDatasourceWithoutWhitelist() {
        when(ticketMapper.selectById(11L)).thenReturn(ticket(900L));
        when(datasourceMapper.selectById(7L)).thenReturn(datasource(900L, " "));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.start(11L, 7L, 900L, 5L));

        // fail-closed:白名单为空等于全库可查,不得用于 RCA
        assertTrue(ex.getMessage().contains("白名单"), "实际=" + ex.getMessage());
        verify(investigationMapper, never()).insert(any(CsInvestigation.class));
    }

    @Test
    void rejectsDatasourceFromOtherWorkspace() {
        when(ticketMapper.selectById(11L)).thenReturn(ticket(900L));
        when(datasourceMapper.selectById(7L)).thenReturn(datasource(800L, "orders,payments"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.start(11L, 7L, 900L, 5L));

        assertTrue(ex.getMessage().contains("不属于当前工作空间"));
    }

    @Test
    void confirmRejectsUnfinishedInvestigation() {
        CsInvestigation inv = new CsInvestigation();
        inv.setId(31L);
        inv.setWorkspaceId(900L);
        inv.setStatus("RUNNING");
        when(investigationMapper.selectById(31L)).thenReturn(inv);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.confirm(31L, 900L, 5L, new CsInvestigationConfirmBo()));

        assertTrue(ex.getMessage().contains("尚未完成"));
        verify(ticketService, never()).addNote(any(), any(), anyString());
    }

    @Test
    void confirmApprovedMarksInvestigationAndNotifiesTicket() {
        CsInvestigation inv = new CsInvestigation();
        inv.setId(31L);
        inv.setWorkspaceId(900L);
        inv.setTicketId(11L);
        inv.setStatus("SUCCEEDED");
        when(investigationMapper.selectById(31L)).thenReturn(inv);
        when(investigationMapper.updateById(any(CsInvestigation.class))).thenReturn(1);

        CsInvestigationConfirmBo bo = new CsInvestigationConfirmBo();
        bo.setApproved(true);

        service.confirm(31L, 900L, 5L, bo);

        assertEquals("CONFIRMED", inv.getStatus());
        assertEquals(5L, inv.getConfirmedBy());
        verify(ticketService).addNote(eq(11L), eq(5L), contains("已采纳"));
    }

    @Test
    void detailRejectsInvestigationFromOtherWorkspace() {
        CsInvestigation inv = new CsInvestigation();
        inv.setId(31L);
        inv.setWorkspaceId(900L);
        when(investigationMapper.selectById(31L)).thenReturn(inv);

        assertThrows(ServiceException.class, () -> service.detail(31L, 800L));
    }
}
