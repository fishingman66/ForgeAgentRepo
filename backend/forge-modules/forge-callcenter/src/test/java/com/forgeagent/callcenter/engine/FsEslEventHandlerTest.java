package com.forgeagent.callcenter.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.service.CallEventDispatcher;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FsEslEventHandler 单测(C1-real):验证 EslEvent -> 业务报文的转换与分发,
 * 不连真实 FreeSWITCH——EslEvent 用 Mockito mock,断言落在传给 dispatcher 的报文内容。
 */
@Tag("dev")
class FsEslEventHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CallcenterProperties properties() {
        CallcenterProperties p = new CallcenterProperties();
        p.getFreeswitch().setChannelCode("CC_FS");
        return p;
    }

    private EslEvent mockEvent(String name, Map<String, String> headers, long ts) {
        EslEvent event = mock(EslEvent.class);
        when(event.getEventName()).thenReturn(name);
        when(event.getEventHeaders()).thenReturn(headers);
        when(event.getEventDateTimestamp()).thenReturn(ts);
        return event;
    }

    @Test
    void resolveEslNamePrefersCustomSubclass() {
        Map<String, String> headers = Map.of("Event-Subclass", "forgeagent::asr_result");
        EslEvent custom = mockEvent("CUSTOM", headers, 0);
        assertEquals("forgeagent::asr_result", FsEslEventHandler.resolveEslName(custom, headers));

        Map<String, String> standard = Map.of("Unique-ID", "x");
        EslEvent ring = mockEvent("CHANNEL_CREATE", standard, 0);
        assertEquals("CHANNEL_CREATE", FsEslEventHandler.resolveEslName(ring, standard));
    }

    @Test
    void toMillisNormalizesSecondsAndMicros() {
        EslEvent event = mockEvent("CHANNEL_CREATE", Map.of(), 0);
        assertEquals(1794278400000L,
            FsEslEventHandler.toMillis(event, Map.of("Event-Date-Timestamp", "1794278400")));
        assertEquals(1794278400123L,
            FsEslEventHandler.toMillis(event, Map.of("Event-Date-Timestamp", "1794278400123")));
        // header 缺失时兜底 getEventDateTimestamp (秒)
        when(event.getEventDateTimestamp()).thenReturn(1794278400L);
        assertEquals(1794278400000L, FsEslEventHandler.toMillis(event, Map.of()));
    }

    @Test
    void handleBuildsNormalizedEnvelopeAndDispatches() throws Exception {
        CallEventDispatcher dispatcher = mock(CallEventDispatcher.class);
        when(dispatcher.dispatchTrusted(anyString(), anyString(), anyString())).thenReturn(1);
        FsEslEventHandler handler = new FsEslEventHandler(dispatcher, properties());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Unique-ID", "FS-REAL-0002");
        headers.put("Caller-Caller-ID-Number", "13800000000");
        headers.put("Caller-Destination-Number", "1001");
        headers.put("Event-Date-Timestamp", "1794278400");
        EslEvent event = mockEvent("CHANNEL_CREATE", headers, 0);

        handler.handle("127.0.0.1:8021", event);

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).dispatchTrusted(eq("FS"), eq("CC_FS"), bodyCap.capture());
        JsonNode envelope = mapper.readTree(bodyCap.getValue());
        JsonNode item = envelope.path("events").get(0);
        assertEquals("CHANNEL_CREATE", item.path("eslEventName").asText());
        assertEquals(1794278400000L, item.path("eventGenTimeMs").asLong());
        assertEquals("FS-REAL-0002", item.path("headers").path("Unique-ID").asText());
    }

    @Test
    void handleMapsCustomSubclassAndWorkNo() throws Exception {
        CallEventDispatcher dispatcher = mock(CallEventDispatcher.class);
        when(dispatcher.dispatchTrusted(anyString(), anyString(), anyString())).thenReturn(1);
        FsEslEventHandler handler = new FsEslEventHandler(dispatcher, properties());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Event-Subclass", "callcenter::agent_state");
        headers.put("Unique-ID", "FS-REAL-0003");
        headers.put("CC-Agent", "SEAT-009");
        EslEvent event = mockEvent("CUSTOM", headers, 1794278400000L);

        handler.handle("addr", event);

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).dispatchTrusted(eq("FS"), eq("CC_FS"), bodyCap.capture());
        JsonNode item = mapper.readTree(bodyCap.getValue()).path("events").get(0);
        assertEquals("callcenter::agent_state", item.path("eslEventName").asText());
        assertEquals("SEAT-009", item.path("workNo").asText());
    }

    @Test
    void handleSwallowsDispatcherException() {
        CallEventDispatcher dispatcher = mock(CallEventDispatcher.class);
        doThrow(new RuntimeException("boom")).when(dispatcher)
            .dispatchTrusted(anyString(), anyString(), anyString());
        FsEslEventHandler handler = new FsEslEventHandler(dispatcher, properties());

        EslEvent event = mockEvent("CHANNEL_CREATE", Map.of("Unique-ID", "x"), 0);
        handler.handle("addr", event); // 不外抛即通过
        assertTrue(true);
    }

    @Test
    void handleIgnoresEmptyHeaders() {
        CallEventDispatcher dispatcher = mock(CallEventDispatcher.class);
        FsEslEventHandler handler = new FsEslEventHandler(dispatcher, properties());
        EslEvent event = mockEvent("HEARTBEAT", Map.of(), 0);
        handler.handle("addr", event);
        verify(dispatcher, org.mockito.Mockito.never())
            .dispatchTrusted(anyString(), anyString(), anyString());
    }
}
