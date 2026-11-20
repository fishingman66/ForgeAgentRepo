package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A2 宿主上下文安全契约:pageUrl 只接受 http/https 并去掉 query/hash,防把 token 写进库。 */
@Tag("dev")
class CsVisitorContextTest {

    @Test
    void keepsOnlyHttpUrlWithoutQueryAndHash() {
        assertEquals("https://shop.example.com/order/1",
            CsVisitorService.safeSourceUrl("https://shop.example.com/order/1?token=secret#pay"));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertNull(CsVisitorService.safeSourceUrl("javascript:alert(1)"));
        assertNull(CsVisitorService.safeSourceUrl("file:///etc/passwd"));
        assertNull(CsVisitorService.safeSourceUrl("data:text/html,x"));
        assertNull(CsVisitorService.safeSourceUrl("https:///no-host"));
    }

    @Test
    void rejectsBlankAndTruncatesLongUrl() {
        assertNull(CsVisitorService.safeSourceUrl(null));
        assertNull(CsVisitorService.safeSourceUrl("  "));
        String longUrl = "https://a.example.com/" + "x".repeat(600);
        assertEquals(500, CsVisitorService.safeSourceUrl(longUrl).length());
    }
}
