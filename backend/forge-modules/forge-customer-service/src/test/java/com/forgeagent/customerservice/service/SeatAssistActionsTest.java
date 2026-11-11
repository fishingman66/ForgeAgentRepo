package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A4:建议动作生成与快照匹配口径(白名单 / 链接提取 / 工单条件)。 */
@Tag("dev")
class SeatAssistActionsTest {

    private Map<String, Object> item(String content) {
        return Map.of("type", "faq", "title", "问题", "content", content, "score", 1.00);
    }

    @Test
    void replyActionFollowsFirstCandidate() {
        List<Map<String, Object>> actions = SeatAssistActions.build(
            List.of(item("支持七天无理由退货，请在订单页发起退货申请。")), false);
        assertEquals(1, actions.size());
        assertEquals("REPLY", actions.get(0).get("code"));
        assertTrue(SeatAssistActions.contentOf(actions.get(0)).contains("七天无理由"));
    }

    @Test
    void linkActionGeneratedOnlyWhenCandidateCarriesUrl() {
        List<Map<String, Object>> withUrl = SeatAssistActions.build(
            List.of(item("退货政策详见 https://example.com/return-policy 页面")), false);
        assertEquals(2, withUrl.size());
        Map<String, Object> link = SeatAssistActions.find(withUrl, "LINK");
        assertNotNull(link);
        assertEquals("https://example.com/return-policy", link.get("payload") instanceof Map<?, ?> payload
            ? String.valueOf(payload.get("url")) : null);

        List<Map<String, Object>> noUrl = SeatAssistActions.build(List.of(item("没有链接的话术")), false);
        assertNull(SeatAssistActions.find(noUrl, "LINK"));
    }

    @Test
    void resolveActionOnlyWithTicket() {
        List<Map<String, Object>> withTicket = SeatAssistActions.build(List.of(item("话术")), true);
        assertNotNull(SeatAssistActions.find(withTicket, "RESOLVE"));
        List<Map<String, Object>> withoutTicket = SeatAssistActions.build(List.of(item("话术")), false);
        assertNull(SeatAssistActions.find(withoutTicket, "RESOLVE"));
    }

    @Test
    void emptyCandidatesProduceNoActions() {
        assertTrue(SeatAssistActions.build(List.of(), false).isEmpty());
        assertTrue(SeatAssistActions.build(null, false).isEmpty());
        // 有工单但无候选内容:只保留 RESOLVE,不给空话术按钮
        List<Map<String, Object>> ticketOnly = SeatAssistActions.build(List.of(), true);
        assertEquals(1, ticketOnly.size());
        assertEquals("RESOLVE", ticketOnly.get(0).get("code"));
    }

    @Test
    void whitelistRejectsUnknownCodes() {
        assertTrue(SeatAssistActions.isAllowed("REPLY"));
        assertTrue(SeatAssistActions.isAllowed("resolve"));
        assertFalse(SeatAssistActions.isAllowed("DELETE"));
        assertFalse(SeatAssistActions.isAllowed(null));
    }

    @Test
    void findIsCaseInsensitiveAndReturnsNullForMissing() {
        List<Map<String, Object>> actions = SeatAssistActions.build(List.of(item("话术")), true);
        assertNotNull(SeatAssistActions.find(actions, "reply"));
        assertNull(SeatAssistActions.find(actions, "TRIGGER_WORKFLOW"));
    }
}
