package com.forgeagent.callcenter.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 事件指纹单测(V1):期望值由 V0 调试端(Python)按 13.2 / sequence.json 规则生成,
 * Java 与 Python 必须对同一事件算出同一指纹,防止两端实现漂移。
 */
@Tag("dev")
class CallFingerprintsTest {

    @Test
    void matchesPythonReferenceVector() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", "MOCK-CALL-20261109-0001");
        content.put("caller", "138****5678");
        content.put("callee", "400****100");
        content.put("ani", "138****5678");
        content.put("dnis", "400****100");
        content.put("direction", "inbound");
        assertEquals("19401f9a34af576868c276e12337bbd197d2b7ada93129defadac42f9b9763c7",
            CallFingerprints.of("MOCK-CALL-20261109-0001", "callRing", 1794182400000L, content));
    }

    @Test
    void keyOrderDoesNotChangeFingerprint() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("callId", "C1");
        one.put("digits", "1");
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("digits", "1");
        two.put("callId", "C1");
        assertEquals(CallFingerprints.of("C1", "dtmf", 1L, one),
            CallFingerprints.of("C1", "dtmf", 1L, two));
    }

    @Test
    void differentContentYieldsDifferentFingerprint() {
        Map<String, Object> content = Map.of("callId", "C1");
        assertNotEquals(CallFingerprints.of("C1", "cdr", 1L, content),
            CallFingerprints.of("C1", "cdr", 2L, content));
        assertNotEquals("fp-error", CallFingerprints.of("C1", "cdr", 1L, null));
    }
}
