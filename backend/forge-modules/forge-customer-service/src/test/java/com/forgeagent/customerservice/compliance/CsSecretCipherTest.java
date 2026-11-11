package com.forgeagent.customerservice.compliance;

import com.forgeagent.customerservice.config.CsModuleProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4:渠道密钥加密往返与存量明文兼容。 */
@Tag("dev")
class CsSecretCipherTest {

    private CsSecretCipher cipher() {
        return new CsSecretCipher(new CsModuleProperties());
    }

    @Test
    void encryptDecryptRoundTrip() {
        String encrypted = cipher().encrypt("token123");
        assertTrue(encrypted.startsWith("ENC:"));
        assertNotEquals("token123", encrypted);
        assertEquals("token123", cipher().decrypt(encrypted));
    }

    @Test
    void eachEncryptionUsesFreshIv() {
        assertNotEquals(cipher().encrypt("token123"), cipher().encrypt("token123"));
    }

    @Test
    void legacyPlainValueStillWorks() {
        assertEquals("legacy-token", cipher().decrypt("legacy-token"));
        assertEquals(null, cipher().decrypt(null));
    }
}
