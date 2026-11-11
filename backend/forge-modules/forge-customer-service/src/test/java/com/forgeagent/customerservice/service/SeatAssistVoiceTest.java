package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A2:语音触发判定口径(定稿 / 说话人 / 置信度)。 */
@Tag("dev")
class SeatAssistVoiceTest {

    private static final double MIN = 0.6;

    @Test
    void intermediateSegmentNeverTriggers() {
        assertFalse(SeatAssistService.acceptsVoice("caller", 0.99, false, MIN));
    }

    @Test
    void lowConfidenceIsRejected() {
        assertFalse(SeatAssistService.acceptsVoice("caller", 0.35, true, MIN));
        assertFalse(SeatAssistService.acceptsVoice("caller", 0.59, true, MIN));
    }

    @Test
    void customerFinalSegmentTriggers() {
        assertTrue(SeatAssistService.acceptsVoice("caller", 0.93, true, MIN));
        assertTrue(SeatAssistService.acceptsVoice("CUSTOMER", 0.6, true, MIN));
    }

    @Test
    void agentAndBotSpeechNeverTriggers() {
        assertFalse(SeatAssistService.acceptsVoice("agent", 0.99, true, MIN));
        assertFalse(SeatAssistService.acceptsVoice("BOT", 0.99, true, MIN));
        assertFalse(SeatAssistService.acceptsVoice("seat", 0.99, true, MIN));
        assertFalse(SeatAssistService.acceptsVoice("ivr", 0.99, true, MIN));
    }

    @Test
    void nullSpeakerTreatedAsCustomerAndNullConfidencePasses() {
        assertTrue(SeatAssistService.acceptsVoice(null, null, true, MIN));
        assertTrue(SeatAssistService.acceptsVoice("", 0.9, true, MIN));
    }
}
