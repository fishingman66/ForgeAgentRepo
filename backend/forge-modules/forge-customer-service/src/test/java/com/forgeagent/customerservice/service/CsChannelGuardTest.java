package com.forgeagent.customerservice.service;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** C0:访客 HMAC 令牌的签发、校验与越权/篡改拒绝。 */
@Tag("dev")
class CsChannelGuardTest {

    private CsChannelGuard guard() {
        return new CsChannelGuard(null, new CsModuleProperties(), null);
    }

    @Test
    void tokenRoundTrip() {
        String token = guard().issueVisitorToken("demo", 42L);
        assertEquals(42L, guard().verifyVisitorToken(token, "demo"));
    }

    @Test
    void rejectsTokenFromAnotherChannel() {
        String token = guard().issueVisitorToken("demo", 42L);
        assertThrows(ServiceException.class, () -> guard().verifyVisitorToken(token, "other"));
    }

    @Test
    void rejectsTamperedAndGarbageToken() {
        String token = guard().issueVisitorToken("demo", 42L);
        assertThrows(ServiceException.class, () -> guard().verifyVisitorToken(token + "x", "demo"));
        assertThrows(ServiceException.class, () -> guard().verifyVisitorToken("not-a-token", "demo"));
    }
}
