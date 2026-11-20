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

import java.util.List;

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

    /** A3:@提及要生成指向目标坐席的 MENTION 事件,且排除自己。 */
    @Test
    void noteWithMentionCreatesTargetedEvent() {
        when(ticketMapper.selectById(4L)).thenReturn(ticket(4L));
        service.addNote(4L, 42L, "请 @7 跟进,我自己 @42 不用提醒");

        ArgumentCaptor<CsTicketEvent> cap = ArgumentCaptor.forClass(CsTicketEvent.class);
        verify(eventMapper, times(2)).insert(cap.capture());
        CsTicketEvent note = cap.getAllValues().get(0);
        CsTicketEvent mention = cap.getAllValues().get(1);
        assertEquals("NOTE", note.getEventType());
        assertEquals("MENTION", mention.getEventType());
        assertEquals(7L, mention.getTargetUserId());
        assertEquals("42", mention.getActorId());
        // 提及自己不再生成第二条 MENTION
        assertEquals(1, cap.getAllValues().stream().filter(e -> "MENTION".equals(e.getEventType())).count());
    }

    @Test
    void parseMentionsDedupesAndCaps() {
        assertEquals(List.of(7L, 8L), CsTicketService.parseMentions("@7 @8 @7", 42L));
        assertEquals(List.of(), CsTicketService.parseMentions("@42", 42L));
        assertEquals(5, CsTicketService.parseMentions("@1 @2 @3 @4 @5 @6", 99L).size());
    }
}
