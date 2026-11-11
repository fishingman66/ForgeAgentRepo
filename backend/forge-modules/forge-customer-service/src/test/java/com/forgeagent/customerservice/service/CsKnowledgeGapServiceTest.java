package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P1:知识缺口优先级升级规则。 */
@Tag("dev")
class CsKnowledgeGapServiceTest {

    @Test
    void priorityEscalatesByHitCount() {
        assertEquals("P2", CsKnowledgeGapService.priorityOf(1));
        assertEquals("P2", CsKnowledgeGapService.priorityOf(2));
        assertEquals("P1", CsKnowledgeGapService.priorityOf(3));
        assertEquals("P1", CsKnowledgeGapService.priorityOf(4));
        assertEquals("P0", CsKnowledgeGapService.priorityOf(5));
        assertEquals("P0", CsKnowledgeGapService.priorityOf(100));
    }
}
