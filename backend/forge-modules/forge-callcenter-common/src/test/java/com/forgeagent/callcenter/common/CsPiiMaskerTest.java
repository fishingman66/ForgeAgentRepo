package com.forgeagent.callcenter.common;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4:PII 脱敏边界。 */
@Tag("dev")
class CsPiiMaskerTest {

    @Test
    void masksPhoneIdCardAndEmail() {
        String raw = "请回电 13812345678，身份证 310101199001011234，邮箱 zhangsan@example.com";
        String masked = CsPiiMasker.mask(raw);
        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("310101199001011234"));
        assertFalse(masked.contains("zhangsan@example.com"));
        assertTrue(masked.contains("138****5678"));
        assertTrue(masked.contains("310101********1234"));
    }

    @Test
    void keepsPlainTextUntouched() {
        assertEquals("我想咨询退货政策", CsPiiMasker.mask("我想咨询退货政策"));
        assertEquals(null, CsPiiMasker.mask(null));
        assertEquals("", CsPiiMasker.mask(""));
    }
}
