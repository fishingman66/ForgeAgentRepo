package com.forgeagent.rag.service.support;

import com.forgeagent.rag.config.KnowledgeProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片识别模型 API Key 加密单测:AES-GCM 往返、密文前缀、掩码、明文兼容
 *
 * @author ForgeAgent
 */
@Tag("dev")
class KbSecretCipherTest {

    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final KbSecretCipher cipher = new KbSecretCipher(properties);

    @Test
    void encryptProducesPrefixAndDecryptsBack() {
        String plain = "sk-test-1234567890abcd";
        String stored = cipher.encrypt(plain);
        assertTrue(stored.startsWith("ENC:"), stored);
        assertFalse(stored.contains(plain), "密文不得包含明文");
        assertEquals(plain, cipher.decrypt(stored));
    }

    @Test
    void encryptIsIdempotentForAlreadyEncrypted() {
        String stored = cipher.encrypt("sk-abc");
        assertEquals(stored, cipher.encrypt(stored));
    }

    @Test
    void plainLegacyValuePassesThrough() {
        assertEquals("legacy-plain-key", cipher.decrypt("legacy-plain-key"));
        assertEquals(null, cipher.decrypt(null));
    }

    @Test
    void maskKeepsTailFour() {
        assertEquals("****abcd", KbSecretCipher.mask("sk-xxxxabcd"));
        assertEquals("****", KbSecretCipher.mask("abc"));
        assertEquals("", KbSecretCipher.mask(""));
        assertEquals("", KbSecretCipher.mask(null));
    }

    @Test
    void differentKeysCannotDecrypt() {
        String stored = cipher.encrypt("sk-secret");
        KnowledgeProperties other = new KnowledgeProperties();
        other.setSecretKey("another-secret-key");
        KbSecretCipher otherCipher = new KbSecretCipher(other);
        // 换密钥后解密失败按原样返回(不抛异常),避免整条链路被打断
        assertEquals(stored, otherCipher.decrypt(stored));
    }
}
