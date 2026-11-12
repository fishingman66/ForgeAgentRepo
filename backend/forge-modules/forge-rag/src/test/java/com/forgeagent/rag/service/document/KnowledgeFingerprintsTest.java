package com.forgeagent.rag.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 配置指纹:语义等价的配置必须同指纹(否则改一下键顺序就全库标脏),
 * 语义变化必须换指纹(否则标脏漏报)。
 */
@Tag("dev")
class KnowledgeFingerprintsTest {

    @Test
    void semanticallyEquivalentCleanRulesGiveSameParseFingerprint() {
        String a = KnowledgeFingerprints.parseOf(null);
        String b = KnowledgeFingerprints.parseOf("{}");
        String c = KnowledgeFingerprints.parseOf("{\"headerFooter\":true,\"blankNormalize\":true}");
        assertEquals(a, b, "缺省与空对象语义相同");
        assertEquals(a, c, "显式默认值与缺省语义相同");
    }

    @Test
    void keyOrderDoesNotAffectParseFingerprint() {
        String a = KnowledgeFingerprints.parseOf("{\"piiMask\":true,\"nfkc\":true}");
        String b = KnowledgeFingerprints.parseOf("{\"nfkc\":true,\"piiMask\":true}");
        assertEquals(a, b, "键顺序不影响指纹");
    }

    @Test
    void semanticChangeChangesParseFingerprint() {
        String base = KnowledgeFingerprints.parseOf("{\"piiMask\":false}");
        String changed = KnowledgeFingerprints.parseOf("{\"piiMask\":true}");
        assertNotEquals(base, changed, "清洗规则变化必须换指纹,否则标脏漏报");
    }

    @Test
    void chunkFingerprintTracksEveryRelevantParam() {
        String base = KnowledgeFingerprints.chunkOf("char", "structure", 500, 50, "0", 2000);
        assertEquals(base, KnowledgeFingerprints.chunkOf("char", "structure", 500, 50, "0", 2000),
            "同配置同指纹");
        assertNotEquals(base, KnowledgeFingerprints.chunkOf("token", "structure", 500, 50, "0", 2000));
        assertNotEquals(base, KnowledgeFingerprints.chunkOf("char", "fixed", 500, 50, "0", 2000));
        assertNotEquals(base, KnowledgeFingerprints.chunkOf("char", "structure", 400, 50, "0", 2000));
        assertNotEquals(base, KnowledgeFingerprints.chunkOf("char", "structure", 500, 80, "0", 2000));
        assertNotEquals(base, KnowledgeFingerprints.chunkOf("char", "structure", 500, 50, "1", 2000));
        assertNotEquals(base, KnowledgeFingerprints.chunkOf("char", "structure", 500, 50, "0", 3000));
    }

    @Test
    void nullAndExplicitValueDifferButAreBothStable() {
        // "缺省 500"与"显式 500"语义等价但指纹不同:代价只是一次无害的重建,换来实现不与默认逻辑耦合
        String missing = KnowledgeFingerprints.chunkOf(null, null, null, null, null, null);
        String explicit = KnowledgeFingerprints.chunkOf("char", "structure", 500, 50, "0", 2000);
        assertNotEquals(missing, explicit);
        assertEquals(missing, KnowledgeFingerprints.chunkOf(null, null, null, null, null, null));
    }

    @Test
    void vlmSwitchParticipatesInParseFingerprint() {
        // R5-5:开关/模型变化必须换指纹,否则改开关不会触发重建
        String off = KnowledgeFingerprints.parseOf(null, "off");
        String onA = KnowledgeFingerprints.parseOf(null, "on:vision-a");
        String onB = KnowledgeFingerprints.parseOf(null, "on:vision-b");
        assertNotEquals(off, onA);
        assertNotEquals(onA, onB);
        assertEquals(off, KnowledgeFingerprints.parseOf(null));
        assertEquals("off", KnowledgeFingerprints.vlmSignature(false, 1, "vision-a"));
        assertEquals("off", KnowledgeFingerprints.vlmSignature(true, 0, "vision-a"));
        assertEquals("on:vision-a", KnowledgeFingerprints.vlmSignature(true, 1, "vision-a"));
    }
}
