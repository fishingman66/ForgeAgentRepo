package com.forgeagent.callcenter.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AICC 网关单测(V1)。签名期望值由 V0 调试端同一算法(Python)生成,
 * 保证 Java 与调试脚本跨语言一致;解析用 V0 fixture 的真实报文。
 */
@Tag("dev")
class AiccGatewayTest {

    private static final String SECRET = "dev-only-aicc-mock-secret-2026";
    private static final String TIMESTAMP = "1794182400";
    private static final String NONCE = "v1-java-vector";
    private static final String SIGNATURE = "FA1p8ADB9Vj7vGG0QYbhMjMnWt_vnij-V9YuPr__uds";
    private static final String RING_BODY =
        "{\"workNo\":\"\",\"eventList\":[{\"eventType\":\"callRing\",\"workNo\":\"\",\"eventGenTime\":1794182400000,"
            + "\"content\":{\"callId\":\"MOCK-CALL-20261109-0001\",\"caller\":\"138****5678\","
            + "\"callee\":\"400****100\",\"ani\":\"138****5678\",\"dnis\":\"400****100\","
            + "\"direction\":\"inbound\"}}]}";

    private final AiccGateway gateway = new AiccGateway();

    @Test
    void verifyMatchesPythonReferenceVector() {
        assertTrue(gateway.verify(SECRET, TIMESTAMP, NONCE, RING_BODY, SIGNATURE));
        assertFalse(gateway.verify(SECRET, TIMESTAMP, NONCE, RING_BODY, SIGNATURE.substring(0, 20) + "X"));
        assertFalse(gateway.verify(null, TIMESTAMP, NONCE, RING_BODY, SIGNATURE));
        assertFalse(gateway.verify(SECRET, TIMESTAMP, NONCE, null, SIGNATURE));
    }

    @Test
    void parseMapsVendorEventsToNormalizedNames() {
        List<CsVoiceEvent> events = gateway.parse("AICC_MOCK", "CC_MOCK", RING_BODY);
        assertEquals(1, events.size());
        CsVoiceEvent ring = events.get(0);
        assertEquals("call.ring", ring.type());
        assertEquals("callRing", ring.rawType());
        assertEquals("MOCK-CALL-20261109-0001", ring.callId());
        assertEquals(1794182400000L, ring.eventGenTime());
        assertEquals("138****5678", ring.str("caller"));
        assertEquals("inbound", ring.str("direction"));
    }

    @Test
    void parseSkipsUnknownEventTypeAndEmptyList() {
        assertEquals(0, gateway.parse("AICC_MOCK", "CC_MOCK", "{\"workNo\":\"\",\"eventList\":[]}").size());
        assertEquals(0, gateway.parse("AICC_MOCK", "CC_MOCK", "{\"workNo\":\"\",\"eventList\":[]}").size());
        List<CsVoiceEvent> events = gateway.parse("AICC_MOCK", "CC_MOCK",
            "{\"workNo\":\"\",\"eventList\":[{\"eventType\":\"mystery\",\"eventGenTime\":1,\"content\":{}}]}");
        assertEquals(0, events.size());
    }

    @Test
    void parseRejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class,
            () -> gateway.parse("AICC_MOCK", "CC_MOCK", "not-json"));
    }

    @Test
    void fetchRecordingReturnsFixedSilentWav() {
        byte[] bytes = gateway.fetchRecording("mock://wav/8k-mono-1s");
        assertNotNull(bytes);
        assertEquals(16044, bytes.length);
        assertArrayEquals(new byte[]{'R', 'I', 'F', 'F'},
            new byte[]{bytes[0], bytes[1], bytes[2], bytes[3]});
        // 数据区全静音
        for (int i = 44; i < bytes.length; i++) {
            if (bytes[i] != 0) {
                throw new AssertionError("静音数据区出现非零字节 at " + i);
            }
        }
        assertThrows(UnsupportedOperationException.class,
            () -> gateway.fetchRecording("https://real-aicc/rec/1.mp3"));
    }
}
