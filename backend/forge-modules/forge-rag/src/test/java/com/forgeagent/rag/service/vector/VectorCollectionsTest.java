package com.forgeagent.rag.service.vector;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量集合命名规则:主集合沿用配置名(存量零迁移),其他维度落到带后缀的独立集合。
 */
@Tag("dev")
class VectorCollectionsTest {

    private static final String BASE = "agent_kb_chunk";

    @Test
    void primaryDimensionKeepsLegacyCollectionName() {
        assertEquals(BASE, VectorCollections.nameFor(BASE, 384, 384));
        assertEquals(BASE, VectorCollections.nameFor(BASE, 384, 384), "存量升级后主集合名不变");
    }

    @Test
    void otherDimensionGetsSuffixedCollection() {
        assertEquals(BASE + "_1024", VectorCollections.nameFor(BASE, 1024, 384));
        assertEquals(BASE + "_768", VectorCollections.nameFor(BASE, 768, 384));
    }

    @Test
    void blankOrUnknownDimensionFallsBackToPrimary() {
        assertEquals(BASE, VectorCollections.nameFor(BASE, 0, 384));
        assertEquals(BASE, VectorCollections.nameFor(BASE, -1, 384));
        assertEquals(BASE, VectorCollections.nameFor(null, 384, 384));
        assertEquals(BASE, VectorCollections.nameFor("  ", 384, 384));
    }

    @Test
    void managedCoversPrimaryAndDimSuffixedOnly() {
        assertTrue(VectorCollections.isManaged(BASE, BASE));
        assertTrue(VectorCollections.isManaged(BASE, BASE + "_1024"));
        // 同前缀但不是维度后缀的集合不归本模块管,删除时不能误伤
        assertFalse(VectorCollections.isManaged(BASE, BASE + "_backup"));
        assertFalse(VectorCollections.isManaged(BASE, BASE + "_"));
        assertFalse(VectorCollections.isManaged(BASE, BASE + "_v2_1024"));
        assertFalse(VectorCollections.isManaged(BASE, "other_kb_chunk"));
        assertFalse(VectorCollections.isManaged(BASE, null));
        assertFalse(VectorCollections.isManaged(BASE, ""));
    }

    @Test
    void managedOfFiltersAndSortsDeterministically() {
        List<String> all = List.of("other", BASE + "_1024", BASE, BASE + "_768", BASE + "_backup");
        assertEquals(List.of(BASE, BASE + "_1024", BASE + "_768"),
            VectorCollections.managedOf(BASE, all));
        assertTrue(VectorCollections.managedOf(BASE, List.of()).isEmpty());
        assertTrue(VectorCollections.managedOf(BASE, null).isEmpty());
        assertTrue(VectorCollections.managedOf(BASE, List.of("other")).isEmpty());
    }

    @Test
    void customBaseNameWorks() {
        assertEquals("kb_prod", VectorCollections.nameFor("kb_prod", 384, 384));
        assertEquals("kb_prod_1536", VectorCollections.nameFor("kb_prod", 1536, 384));
        assertTrue(VectorCollections.isManaged("kb_prod", "kb_prod_1536"));
        assertFalse(VectorCollections.isManaged("kb_prod", "kb_production_1536"));
    }
}
