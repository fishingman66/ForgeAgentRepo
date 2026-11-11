package com.forgeagent.agent.service.runner;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 问数数据源连接密码加解密器
 *
 * <p>落库前 AES 加密(密文带 {@value #PREFIX} 前缀),连接池建库时解密;
 * 无前缀的历史明文/解密失败均原样返回,保证兼容与可用性。
 * 生产环境务必通过 {@code agent.datasource.secret} 覆盖默认密钥。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class DatasourceCipher {

    /**
     * 密文前缀,用于区分历史明文
     */
    private static final String PREFIX = "ENC:";

    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";

    private static final String DEFAULT_SECRET = "forgeDsSecret2026";

    @Value("${agent.datasource.secret:forgeDsSecret2026}")
    private String secret;

    private SecretKeySpec keySpec;

    @PostConstruct
    void init() {
        if (DEFAULT_SECRET.equals(secret)) {
            log.warn("[DatasourceCipher] 数据源密码使用默认密钥加密,生产环境请通过 agent.datasource.secret 覆盖");
        }
        // 取密钥 UTF-8 前 16 字节,不足补 '0'
        byte[] raw = StringUtils.defaultString(secret, DEFAULT_SECRET).getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) '0');
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, 16));
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    /**
     * 加密(已是密文或空串则原样返回)
     */
    public String encrypt(String plain) {
        if (StringUtils.isBlank(plain) || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.warn("[DatasourceCipher] 密码加密失败: {}", e.getMessage());
            return plain;
        }
    }

    /**
     * 解密(非密文格式或解密失败均原样返回,兼容历史明文)
     */
    public String decrypt(String stored) {
        if (StringUtils.isBlank(stored) || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[DatasourceCipher] 密码解密失败,按原样使用: {}", e.getMessage());
            return stored;
        }
    }
}
