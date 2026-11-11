package com.forgeagent.customerservice.compliance;

import com.forgeagent.customerservice.config.CsModuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 渠道密钥加密(C4):AES-GCM,密钥由 agent.customer-service.channel-secret-key 派生。
 *
 * <p>存量明文兼容:解密失败时按明文返回,便于平滑迁移;加密结果加 "ENC:" 前缀区分。</p>
 */
@Component
@RequiredArgsConstructor
public class CsSecretCipher {

    private static final String PREFIX = "ENC:";
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CsModuleProperties properties;

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("渠道密钥加密失败", e);
        }
    }

    /** 解密;非加密格式或解密失败时原样返回(兼容存量明文)。 */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return stored;
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(properties.getChannelSecretKey().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
