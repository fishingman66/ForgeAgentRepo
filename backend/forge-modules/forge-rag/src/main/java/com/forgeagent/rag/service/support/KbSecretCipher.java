package com.forgeagent.rag.service.support;

import lombok.RequiredArgsConstructor;
import com.forgeagent.rag.config.KnowledgeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 知识库敏感配置加密(AES-GCM):图片识别模型的 API Key 落库只存密文。
 *
 * <p>密钥由 {@code rag.knowledge.secret-key} 派生(SHA-256),换密钥会导致既有密文无法解密,
 * 因此生产必须显式配置并在上线清单中登记。存量明文兼容:解密失败原样返回。</p>
 *
 * @author ForgeAgent
 */
@Component
@RequiredArgsConstructor
public class KbSecretCipher {

    private static final String PREFIX = "ENC:";
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KnowledgeProperties properties;

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
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /**
     * 解密;非加密格式或解密失败原样返回(兼容存量明文/换密钥后的旧数据)
     */
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

    /**
     * 掩码展示:只保留尾 4 位,空值返回空串
     */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        return plain.length() <= 4 ? "****" : "****" + plain.substring(plain.length() - 4);
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
