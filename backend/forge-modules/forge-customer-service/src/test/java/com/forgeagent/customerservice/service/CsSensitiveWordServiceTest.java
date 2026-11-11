package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0:敏感词级别取高与打码口径。 */
@Tag("dev")
class CsSensitiveWordServiceTest {

    @Test
    void levelTakesTheHighestSeverity() {
        assertEquals(CsSensitiveWordService.Level.BLOCK,
            CsSensitiveWordService.Level.MASK.max(CsSensitiveWordService.Level.BLOCK));
        assertEquals(CsSensitiveWordService.Level.MASK,
            CsSensitiveWordService.Level.WARN.max(CsSensitiveWordService.Level.MASK));
        assertEquals(CsSensitiveWordService.Level.NONE,
            CsSensitiveWordService.Level.NONE.max(CsSensitiveWordService.Level.NONE));
    }

    @Test
    void maskReplacesEveryHitCaseInsensitively() {
        String masked = CsSensitiveWordService.maskText("竞品a 和 竞品A 都不该出现", List.of("竞品A"));
        assertFalse(masked.contains("竞品a"));
        assertFalse(masked.contains("竞品A"));
        assertTrue(masked.contains("***"));
    }

    @Test
    void maskIsSafeOnBlankInput() {
        assertNull(CsSensitiveWordService.maskText(null, List.of("x")));
        assertEquals("正常内容", CsSensitiveWordService.maskText("正常内容", List.of()));
        assertEquals("正常内容", CsSensitiveWordService.maskText("正常内容", List.of(" ", "")));
    }

    @Test
    void blockAndMaskFlagsFollowLevel() {
        assertTrue(new CsSensitiveWordService.Hit(CsSensitiveWordService.Level.BLOCK, List.of("x")).blocked());
        assertTrue(new CsSensitiveWordService.Hit(CsSensitiveWordService.Level.MASK, List.of("x")).masked());
        assertFalse(new CsSensitiveWordService.Hit(CsSensitiveWordService.Level.WARN, List.of("x")).blocked());
        assertFalse(new CsSensitiveWordService.Hit(CsSensitiveWordService.Level.WARN, List.of("x")).masked());
    }
}
