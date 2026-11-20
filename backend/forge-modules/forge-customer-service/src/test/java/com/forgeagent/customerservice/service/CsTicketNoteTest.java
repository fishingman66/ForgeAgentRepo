package com.forgeagent.customerservice.service;

import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsQueueMapper;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.mapper.CsTicketEventMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A3 内部备注安全契约:NOTE 只写工单事件轨迹,绝不进 agent_message / 访客 SSE。 */
@Tag("dev")
class CsTicketNoteTest {

    private CsTicketMapper ticketMapper;
    private CsTicketEventMapper eventMapper;
    private AgentMessageMapper messageMapper;
    private CsRealtimeTransport transport;
    private CsTicketService service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(CsTicketMapper.class);
        eventMapper = mock(CsTicketEventMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        transport = mock(CsRealtimeTransport.class);
        service = new CsTicketService(
            ticketMapper, eventMapper, mock(CsQueueMapper.class), mock(CsSeatMapper.class),
            mock(CsConversationMapper.class), messageMapper, transport,
            mock(CsatService.class), mock(CsQualityService.class),
            mock(CsEvalFeedbackService.class), mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private CsTicket ticket(long id) {
        CsTicket t = new CsTicket();
        t.setId(id);
        t.setStatus("PROCESSING");
        t.setWorkspaceId(7L);
        t.setConversationId(99L);
        return t;
    }

    @Test
    void noteWritesTicketEventOnly() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L));
        service.addNote(1L, 42L, "客户偏好货到付款,后续跟进");

        ArgumentCaptor<CsTicketEvent> cap = ArgumentCaptor.forClass(CsTicketEvent.class);
        verify(eventMapper).insert(cap.capture());
        CsTicketEvent e = cap.getValue();
        assertEquals("NOTE", e.getEventType());
        assertEquals("SEAT", e.getActorType());
        assertEquals("42", e.getActorId());
        assertEquals("客户偏好货到付款,后续跟进", e.getNote());
        assertEquals("PROCESSING", e.getFromStatus());
        assertEquals("PROCESSING", e.getToStatus());
        // 安全核心:内部备注不写消息表、不推任何实时通道
        verifyNoInteractions(messageMapper);
        verifyNoInteractions(transport);
    }

    @Test
    void noteKeepsStatusUnchanged() {
        CsTicket t = ticket(2L);
        t.setStatus("WAITING_CONFIRM");
        when(ticketMapper.selectById(2L)).thenReturn(t);
        service.addNote(2L, 7L, "内部同步");
        ArgumentCaptor<CsTicketEvent> cap = ArgumentCaptor.forClass(CsTicketEvent.class);
        verify(eventMapper).insert(cap.capture());
        assertEquals("WAITING_CONFIRM", cap.getValue().getFromStatus());
        assertEquals("WAITING_CONFIRM", cap.getValue().getToStatus());
        verify(ticketMapper, never()).updateById(any(CsTicket.class));
    }

    @Test
    void noteOnMissingTicketRejected() {
        when(ticketMapper.selectById(3L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.addNote(3L, 1L, "x"));
        verifyNoInteractions(eventMapper);
    }
}
