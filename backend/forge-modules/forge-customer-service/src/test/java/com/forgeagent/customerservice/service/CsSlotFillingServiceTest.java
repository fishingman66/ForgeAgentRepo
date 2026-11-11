package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.CsModuleProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1:槽位抽取口径(自由文本整句赋值 / 正则槽位不受影响)。 */
@Tag("dev")
class CsSlotFillingServiceTest {

    private CsModuleProperties.Slot slot(String name, String pattern) {
        CsModuleProperties.Slot slot = new CsModuleProperties.Slot();
        slot.setName(name);
        slot.setLabel(name);
        slot.setPattern(pattern);
        slot.setRequired(true);
        return slot;
    }

    private final List<CsModuleProperties.Slot> form = List.of(
        slot("orderNo", "\\d{8,}"),
        slot("reason", null));

    @Test
    void freeTextSlotTakesWholeSentence() {
        Map<String, String> merged = CsSlotFillingService.mergeFreeText(
            Map.of("orderNo", "12345678"), form, "reason", "  尺码不合适，想退 ");
        assertEquals("尺码不合适，想退", merged.get("reason"));
        assertEquals("12345678", merged.get("orderNo"));
    }

    @Test
    void patternSlotIsNotFilledByWholeSentence() {
        // 追问的是订单号(带正则)时,不能把整句当订单号
        Map<String, String> merged = CsSlotFillingService.mergeFreeText(
            Map.of(), form, "orderNo", "我的订单好像出问题了");
        assertFalse(merged.containsKey("orderNo"));
    }

    @Test
    void blankInputIsSafe() {
        assertTrue(CsSlotFillingService.mergeFreeText(null, form, null, "x").isEmpty());
        assertEquals(1, CsSlotFillingService.mergeFreeText(Map.of("orderNo", "1"), form, "unknown", "x").size());
    }
}
