package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.domain.CsCallStatus;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 呼叫状态机单测(V1):8 态单调推进,乱序与迟到事件不回退(V0 契约 / 方案 5.5)。
 */
@Tag("dev")
class CallStateMachineTest {

    private final CallStateMachine machine = new CallStateMachine();

    private CsVoiceEvent event(String type, Map<String, Object> content) {
        return new CsVoiceEvent("AICC_MOCK", "CC_MOCK", rawTypeOf(type), type, "SEAT-001",
            1794182400000L, content);
    }

    private String rawTypeOf(String normalized) {
        return switch (normalized) {
            case "call.ring" -> "callRing";
            case "call.answer" -> "callAnswer";
            case "call.asr_result" -> "asrResult";
            case "call.bot_utterance" -> "botUtterance";
            case "call.dtmf" -> "dtmf";
            case "call.transfer" -> "transfer";
            case "call.seat_state" -> "seatState";
            case "call.record.ready" -> "recordReady";
            case "call.hangup" -> "hangup";
            case "call.cdr" -> "cdr";
            default -> normalized;
        };
    }

    @Test
    void fullSequenceAdvancesMonotonically() {
        String state = CsCallStatus.RINGING.name();
        assertEquals("ANSWERED", state = machine.advance(state, machine.targetOf(event("call.answer", Map.of()))));
        assertEquals("BOT_SERVING", state = machine.advance(state, machine.targetOf(event("call.asr_result", Map.of()))));
        assertEquals("BOT_SERVING", state = machine.advance(state, machine.targetOf(event("call.dtmf", Map.of()))));
        assertEquals("WAITING_AGENT", state = machine.advance(state, machine.targetOf(event("call.transfer", Map.of()))));
        assertEquals("AGENT_SERVING", state = machine.advance(state,
            machine.targetOf(event("call.seat_state", Map.of("readyState", "TALKING")))));
        assertEquals("AGENT_SERVING", state = machine.advance(state,
            machine.targetOf(event("call.record.ready", Map.of()))));
        assertEquals("ENDED", state = machine.advance(state, machine.targetOf(event("call.hangup", Map.of()))));
        assertEquals("ENDED", state);
    }

    @Test
    void lateEventsNeverRollBack() {
        assertEquals("ENDED", machine.advance("ENDED", machine.targetOf(event("call.answer", Map.of()))));
        assertEquals("ENDED", machine.advance("ENDED", machine.targetOf(event("call.ring", Map.of()))));
        assertEquals("AGENT_SERVING",
            machine.advance("AGENT_SERVING", machine.targetOf(event("call.transfer", Map.of()))));
        assertEquals("WAITING_AGENT",
            machine.advance("WAITING_AGENT", machine.targetOf(event("call.bot_utterance", Map.of()))));
    }

    @Test
    void seatStateOnlyAdvancesOnTalking() {
        assertNull(machine.targetOf(event("call.seat_state", Map.of("readyState", "READY"))));
        assertNull(machine.targetOf(event("call.seat_state", Map.of("readyState", "PAUSE"))));
        assertEquals("AGENT_SERVING",
            machine.targetOf(event("call.seat_state", Map.of("readyState", "TALKING"))));
    }

    @Test
    void recordReadyAndCdrDoNotAdvance() {
        assertNull(machine.targetOf(event("call.record.ready", Map.of())));
        assertNull(machine.targetOf(event("call.cdr", Map.of())));
        assertEquals("RINGING", machine.advance("RINGING", null));
    }

    @Test
    void statusEnumHandlesUnknownValues() {
        assertNull(CsCallStatus.of(null));
        assertNull(CsCallStatus.of(""));
        assertNull(CsCallStatus.of("NOT_A_STATE"));
        assertEquals(CsCallStatus.ENDED, CsCallStatus.of("ENDED"));
        assertEquals(8, CsCallStatus.values().length);
    }
}
