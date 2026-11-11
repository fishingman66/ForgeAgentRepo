package com.forgeagent.customerservice.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C1:七态工单状态机基础语义。 */
@Tag("dev")
class CsTicketStatusTest {

    @Test
    void hasSevenStates() {
        assertEquals(7, CsTicketStatus.values().length);
    }

    @Test
    void endedStatesCoverResolvedAndClosedOnly() {
        assertTrue(CsTicketStatus.RESOLVED.isEnded());
        assertTrue(CsTicketStatus.CLOSED.isEnded());
        assertFalse(CsTicketStatus.WAITING_CONFIRM.isEnded());
        assertFalse(CsTicketStatus.PROCESSING.isEnded());
    }

    @Test
    void unknownStateFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> CsTicketStatus.of("NOT_A_STATE"));
        assertEquals(CsTicketStatus.PROCESSING, CsTicketStatus.of("processing"));
    }
}
