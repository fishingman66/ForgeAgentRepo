package com.forgeagent.rag.service.support;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5-6 库级检索参数覆盖单测:解析/校验/默认权重
 *
 * @author ForgeAgent
 */
@Tag("dev")
class KnowledgeRetrievalOverridesTest {

    @Test
    void parsesAllKeys() {
        KnowledgeRetrievalOverrides ov = KnowledgeRetrievalOverrides.parse(
            "{\"topK\":8,\"searchMode\":\"keyword\",\"vectorWeight\":0.3,\"keywordWeight\":0.7,"
                + "\"scoreThreshold\":0.42,\"rerank\":true}");
        assertEquals(8, ov.topK());
        assertEquals("keyword", ov.searchMode());
        assertEquals(0.3, ov.vectorWeight(), 1e-9);
        assertEquals(0.7, ov.keywordWeight(), 1e-9);
        assertEquals(0.42, ov.scoreThreshold(), 1e-9);
        assertTrue(ov.rerank());
    }

    @Test
    void blankAndBadJsonFallBackToEmpty() {
        assertNull(KnowledgeRetrievalOverrides.parse(null).topK());
        assertNull(KnowledgeRetrievalOverrides.parse("not json").topK());
        assertEquals(1.0, KnowledgeRetrievalOverrides.EMPTY.vectorWeightOrDefault(), 1e-9);
        assertEquals(1.0, KnowledgeRetrievalOverrides.EMPTY.keywordWeightOrDefault(), 1e-9);
    }

    @Test
    void validateRejectsIllegalValues() {
        assertThrows(ServiceException.class, () -> KnowledgeRetrievalOverrides.validate("[]"));
        assertThrows(ServiceException.class, () -> KnowledgeRetrievalOverrides.validate("{\"topK\":99}"));
        assertThrows(ServiceException.class, () -> KnowledgeRetrievalOverrides.validate("{\"searchMode\":\"fuzzy\"}"));
        assertThrows(ServiceException.class, () -> KnowledgeRetrievalOverrides.validate("{\"scoreThreshold\":1.5}"));
        assertThrows(ServiceException.class, () -> KnowledgeRetrievalOverrides.validate("{\"vectorWeight\":-1}"));
    }

    @Test
    void signatureDiffersWhenConfigDiffers() {
        String a = KnowledgeRetrievalOverrides.parse("{\"topK\":5}").signature();
        String b = KnowledgeRetrievalOverrides.parse("{\"topK\":8}").signature();
        assertTrue(!a.equals(b));
    }
}
