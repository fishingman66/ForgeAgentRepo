package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** C2:FAQ 问题摘要归一化(大小写/空白不影响去重)。 */
@Tag("dev")
class CsKnowledgeAnswerServiceTest {

    @Test
    void hashIsStableAndNormalized() {
        String a = CsKnowledgeAnswerService.hash("  退货政策 ");
        String b = CsKnowledgeAnswerService.hash("退货政策");
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void differentQuestionsHaveDifferentHash() {
        assertNotEquals(CsKnowledgeAnswerService.hash("退货政策"),
            CsKnowledgeAnswerService.hash("发票开具"));
    }
}
