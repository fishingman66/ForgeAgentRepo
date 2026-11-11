package com.forgeagent.callcenter.common;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V0.5:固定公共契约层的常量集合,防止文本域与语音域各自漂移。 */
@Tag("dev")
class CsContractConstantsTest {

    @Test
    void textEventsKeepDeliveredValues() {
        assertEquals(13, CsEventTypes.TEXT_EVENTS.size());
        assertEquals(Set.of(
            "chat", "chat_accepted", "chat_chunk", "chat_done",
            "cs_ticket_event", "cs_ticket_new", "cs_queue_position", "cs_handoff",
            "cs_agent_typing", "cs_csat_invite", "cs_seat_suggest", "system", "error"),
            CsEventTypes.TEXT_EVENTS);
    }

    @Test
    void voiceEventsAreStable() {
        assertEquals(9, CsEventTypes.VOICE_EVENTS.size());
        assertTrue(CsEventTypes.VOICE_EVENTS.contains("call.record.ready"));
        assertTrue(CsEventTypes.VOICE_EVENTS.contains("call.transcript"));
    }

    @Test
    void allEventsIsDisjointUnion() {
        assertEquals(22, CsEventTypes.ALL_EVENTS.size());
        Set<String> union = new HashSet<>(CsEventTypes.TEXT_EVENTS);
        union.addAll(CsEventTypes.VOICE_EVENTS);
        assertEquals(union, CsEventTypes.ALL_EVENTS);
        for (String voice : CsEventTypes.VOICE_EVENTS) {
            assertFalse(CsEventTypes.TEXT_EVENTS.contains(voice));
        }
    }

    @Test
    void envelopeCompatibilityConstantsDelegateToSingleSource() {
        assertEquals(CsEventTypes.CHAT, CsEventEnvelope.CHAT);
        assertEquals(CsEventTypes.CHAT_ACCEPTED, CsEventEnvelope.CHAT_ACCEPTED);
        assertEquals(CsEventTypes.CHAT_CHUNK, CsEventEnvelope.CHAT_CHUNK);
        assertEquals(CsEventTypes.CHAT_DONE, CsEventEnvelope.CHAT_DONE);
        assertEquals(CsEventTypes.TICKET_EVENT, CsEventEnvelope.TICKET_EVENT);
        assertEquals(CsEventTypes.TICKET_NEW, CsEventEnvelope.TICKET_NEW);
        assertEquals(CsEventTypes.QUEUE_POSITION, CsEventEnvelope.QUEUE_POSITION);
        assertEquals(CsEventTypes.HANDOFF, CsEventEnvelope.HANDOFF);
        assertEquals(CsEventTypes.AGENT_TYPING, CsEventEnvelope.AGENT_TYPING);
        assertEquals(CsEventTypes.CSAT_INVITE, CsEventEnvelope.CSAT_INVITE);
        assertEquals(CsEventTypes.SYSTEM, CsEventEnvelope.SYSTEM);
        assertEquals(CsEventTypes.ERROR, CsEventEnvelope.ERROR);
    }

    @Test
    void envelopeFactoryKeepsBehaviour() {
        CsEventEnvelope envelope = CsEventEnvelope.of(CsEventTypes.CHAT_DONE, 7L, 9L, "payload");
        assertEquals("chat_done", envelope.type());
        assertEquals(0L, envelope.sequence());
        assertEquals(7L, envelope.conversationId());
        assertEquals(9L, envelope.ticketId());
        assertEquals("payload", envelope.payload());
        assertEquals(3L, envelope.withSequence(3L).sequence());
        assertEquals(envelope.ts(), envelope.withSequence(3L).ts());
    }

    @Test
    void sceneCodesMatchDeliveredSessionScene() {
        assertEquals("cs", CsScene.CS.code());
        assertEquals(CsScene.CS, CsScene.fromCode("CS"));
        assertEquals(CsScene.VOICE, CsScene.fromCode("voice"));
        assertNull(CsScene.fromCode(null));
        assertNull(CsScene.fromCode("unknown"));
    }

    @Test
    void channelTypeCodesMatchSqlEnum() {
        assertEquals(6, CsChannelType.values().length);
        assertEquals("wechat_mp", CsChannelType.WECHAT_MP.code());
        assertEquals(CsChannelType.WIDGET, CsChannelType.fromCode("widget"));
        assertEquals(CsChannelType.VOICE_INBOUND, CsChannelType.fromCode("VOICE_INBOUND"));
        assertNull(CsChannelType.fromCode(null));
        assertNull(CsChannelType.fromCode("unknown"));
    }

    @Test
    void defaultsKeepDeliveredConfigurationPrefix() {
        assertEquals("agent.customer-service", CsDefaults.PREFIX_CUSTOMER_SERVICE);
        assertEquals("agent.callcenter", CsDefaults.PREFIX_CALLCENTER);
        assertEquals(300, CsDefaults.CHANNEL_CALLBACK_TOLERANCE_SECONDS);
        assertEquals(180, CsDefaults.RETENTION_DAYS);
    }
}
