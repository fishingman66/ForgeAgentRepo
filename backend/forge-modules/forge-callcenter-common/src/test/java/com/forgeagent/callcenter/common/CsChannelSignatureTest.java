package com.forgeagent.callcenter.common;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C3:渠道签名与时间窗。 */
@Tag("dev")
class CsChannelSignatureTest {

    @Test
    void sha1IsOrderIndependent() {
        assertEquals(CsChannelSignature.sha1("token", "1700000000", "abc"),
            CsChannelSignature.sha1("abc", "token", "1700000000"));
    }

    @Test
    void timeWindowRejectsOldTimestamp() {
        String now = String.valueOf(Instant.now().getEpochSecond());
        assertTrue(CsChannelSignature.withinTolerance(now, 300));
        assertFalse(CsChannelSignature.withinTolerance("1", 300));
        assertFalse(CsChannelSignature.withinTolerance("not-a-number", 300));
    }

    @Test
    void constantTimeEqualsHandlesNull() {
        assertFalse(CsChannelSignature.constantTimeEquals(null, "x"));
        assertFalse(CsChannelSignature.constantTimeEquals("x", null));
        assertTrue(CsChannelSignature.constantTimeEquals("abc", "abc"));
        assertFalse(CsChannelSignature.constantTimeEquals("abc", "abd"));
    }
}
