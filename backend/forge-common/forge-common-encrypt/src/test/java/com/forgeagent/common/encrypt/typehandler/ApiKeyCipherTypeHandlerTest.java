package com.forgeagent.common.encrypt.typehandler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3-1:API Key 落库 AES-GCM TypeHandler 单测。
 *
 * <p>验证:密文往返、ENC 前缀幂等、存量明文兼容、每次加密 IV 随机。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
class ApiKeyCipherTypeHandlerTest {

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "sk-proj-abcdefghijklmnopqrstuvwxyz";
        String stored = ApiKeyCipherTypeHandler.encrypt(plain);

        assertTrue(stored.startsWith(ApiKeyCipherTypeHandler.PREFIX));
        assertNotEquals(plain, stored);
        assertEquals(plain, ApiKeyCipherTypeHandler.decrypt(stored));
    }

    @Test
    void encryptIsIdempotentOnCiphertext() {
        String once = ApiKeyCipherTypeHandler.encrypt("sk-test-key");
        String twice = ApiKeyCipherTypeHandler.encrypt(once);

        assertEquals(once, twice, "已加密的值不得二次加密");
    }

    @Test
    void decryptPassesThroughPlaintextForBackwardCompat() {
        String legacy = "sk-legacy-plain-key";
        assertEquals(legacy, ApiKeyCipherTypeHandler.decrypt(legacy));
    }

    @Test
    void eachEncryptUsesFreshIv() {
        String a = ApiKeyCipherTypeHandler.encrypt("sk-same-key");
        String b = ApiKeyCipherTypeHandler.encrypt("sk-same-key");

        assertNotEquals(a, b, "GCM 每次应使用随机 IV");
        assertEquals("sk-same-key", ApiKeyCipherTypeHandler.decrypt(a));
        assertEquals("sk-same-key", ApiKeyCipherTypeHandler.decrypt(b));
    }

    @Test
    void blankValuesStayBlank() {
        assertEquals("", ApiKeyCipherTypeHandler.encrypt(""));
        assertEquals(null, ApiKeyCipherTypeHandler.decrypt(null));
    }
}
