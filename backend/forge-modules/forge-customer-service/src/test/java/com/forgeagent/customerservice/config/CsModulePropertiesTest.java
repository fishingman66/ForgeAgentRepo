package com.forgeagent.customerservice.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S0:模块开关与通道参数默认值(纯对象,不启 Spring 上下文)。
 */
@Tag("dev")
class CsModulePropertiesTest {

    @Test
    void defaultsKeepConsoleBehaviorUntouched() {
        CsModuleProperties p = new CsModuleProperties();
        assertTrue(p.getEnabled());
        assertEquals("sse", p.getTransport());
        assertEquals(1800, p.getVisitorTokenTtlSeconds());
        assertEquals(30, p.getSessionIdleMinutes());
        assertEquals(60, p.getChannelRateLimitPerMinute());
        assertEquals(4000, p.getMaxMessageChars());
    }

    @Test
    void transportCanSwitchToWebsocketForVoicePhase() {
        CsModuleProperties p = new CsModuleProperties();
        p.setTransport("websocket");
        assertEquals("websocket", p.getTransport());
    }
}
