package com.forgeagent.common.encrypt.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

/**
 * API Key 落库加密 TypeHandler(AES-GCM)。
 *
 * <p>写库自动加 {@code ENC:} 前缀 + 12 字节随机 IV + AES-GCM(tag 128) + base64url;
 * 读库遇到 ENC: 解密,其余(存量明文)原样返回——天然兼容存量数据,无需停机迁移。
 * 密钥来自环境变量 {@code AGENT_KEY_SECRET}(或系统属性同名),未配置时用内置默认值并保证
 * 生产部署在上线清单中显式覆盖(与 rag.knowledge.secret-key 同纪律)。</p>
 *
 * <p>放在 common-encrypt 横切层:实体仅需 @TableField(typeHandler=...),运行时所有读取点
 * 透明拿到明文,写入点零改动。</p>
 *
 * @author ForgeAgent
 */
public class ApiKeyCipherTypeHandler extends BaseTypeHandler<String> {

    public static final String PREFIX = "ENC:";
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ENV_KEY = "AGENT_KEY_SECRET";
    private static final String DEFAULT_SECRET = "forge-agent-model-key-change-me";

    private static SecretKeySpec key() throws Exception {
        String secret = System.getProperty(ENV_KEY);
        if (secret == null || secret.isBlank()) {
            secret = System.getenv(ENV_KEY);
        }
        if (secret == null || secret.isBlank()) {
            secret = DEFAULT_SECRET;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    /** 加密;明文为 null/空/已带前缀时原样返回 */
    public static String encrypt(String plain) {
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

    /** 解密;非加密格式或解密失败原样返回(兼容存量明文/换密钥后的旧数据) */
    public static String decrypt(String stored) {
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

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }
}
