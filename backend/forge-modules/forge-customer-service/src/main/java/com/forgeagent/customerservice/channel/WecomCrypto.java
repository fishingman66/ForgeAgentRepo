package com.forgeagent.customerservice.channel;

import com.forgeagent.common.core.exception.ServiceException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * P3 / B5:企业微信「安全模式」报文加解密。
 *
 * <p>规范(与企微官方一致):</p>
 * <pre>
 * AESKey       = Base64Decode(EncodingAESKey + "=")            // 32 字节
 * IV           = AESKey[0..16]
 * 明文         = random(16) + msg_len(4, 大端) + msg + receiveid
 * 填充         = PKCS#7,块长 32
 * 密文         = Base64(AES-256-CBC(明文))
 * msg_signature= SHA1(sort(token, timestamp, nonce, encrypt))
 * </pre>
 *
 * <p>与明文的边界:<b>只要渠道配了 EncodingAESKey,就必须走加密链路</b>;
 * 解密/验签失败一律拒绝,<b>绝不回退明文</b>——否则等于给攻击者留一个降级面。</p>
 *
 * @author ForgeAgent
 */
public final class WecomCrypto {

    /** 企微固定块长 */
    private static final int BLOCK_SIZE = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WecomCrypto() {
    }

    /**
     * 解密企微密文。
     *
     * @param encodingAesKey 43 字符 EncodingAESKey(渠道配置,已解密取出)
     * @param corpId         期望的 receiveid(企微 CorpID;第三方平台场景为 suiteId)
     * @param encrypted      密文(Base64)
     * @return 明文消息体(通常是 XML)
     */
    public static String decrypt(String encodingAesKey, String corpId, String encrypted) {
        if (isBlank(encodingAesKey) || isBlank(encrypted)) {
            throw new ServiceException("企微加密模式缺少 EncodingAESKey 或密文");
        }
        byte[] aesKey = aesKey(encodingAesKey);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(aesKey, 0, 16));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypted.trim()));
            plain = pkcs7Unpad(plain);

            if (plain.length < 20) {
                throw new ServiceException("企微密文长度异常");
            }
            int msgLen = ((plain[16] & 0xff) << 24) | ((plain[17] & 0xff) << 16)
                | ((plain[18] & 0xff) << 8) | (plain[19] & 0xff);
            if (msgLen < 0 || 20 + msgLen > plain.length) {
                throw new ServiceException("企微密文消息长度字段非法");
            }
            String msg = new String(plain, 20, msgLen, StandardCharsets.UTF_8);
            String receiveId = new String(plain, 20 + msgLen, plain.length - 20 - msgLen,
                StandardCharsets.UTF_8);
            if (!isBlank(corpId) && !corpId.equals(receiveId)) {
                throw new ServiceException("企微 receiveid 校验失败(配置的 CorpID 与报文不一致)");
            }
            return msg;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("企微消息解密失败:" + e.getMessage());
        }
    }

    /**
     * 加密回复报文。
     *
     * @param encodingAesKey 43 字符 EncodingAESKey
     * @param corpId         receiveid(企微 CorpID)
     * @param plain          明文 XML
     * @return Base64 密文
     */
    public static String encrypt(String encodingAesKey, String corpId, String plain) {
        if (isBlank(encodingAesKey)) {
            throw new ServiceException("企微加密模式缺少 EncodingAESKey");
        }
        byte[] aesKey = aesKey(encodingAesKey);
        byte[] msg = (plain == null ? "" : plain).getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);

        byte[] buffer = new byte[16 + 4 + msg.length + (corpId == null ? 0 : corpId.getBytes(StandardCharsets.UTF_8).length)];
        System.arraycopy(random, 0, buffer, 0, 16);
        buffer[16] = (byte) ((msg.length >> 24) & 0xff);
        buffer[17] = (byte) ((msg.length >> 16) & 0xff);
        buffer[18] = (byte) ((msg.length >> 8) & 0xff);
        buffer[19] = (byte) (msg.length & 0xff);
        System.arraycopy(msg, 0, buffer, 20, msg.length);
        if (corpId != null) {
            byte[] id = corpId.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(id, 0, buffer, 20 + msg.length, id.length);
        }

        byte[] padded = pkcs7Pad(buffer);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(aesKey, 0, 16));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new ServiceException("企微消息加密失败:" + e.getMessage());
        }
    }

    /**
     * 解析 EncodingAESKey 为 32 字节 AES 密钥。
     *
     * <p>企微字符集是 base64 字母表,但不同实现可能产出 URL-safe 变体,两种都尝试。</p>
     */
    private static byte[] aesKey(String encodingAesKey) {
        String padded = encodingAesKey.trim() + "=";
        byte[] key;
        try {
            key = Base64.getDecoder().decode(padded);
        } catch (Exception e) {
            try {
                key = Base64.getUrlDecoder().decode(padded);
            } catch (Exception ex) {
                throw new ServiceException("EncodingAESKey 不是合法的 Base64 字符串");
            }
        }
        if (key.length != 32) {
            throw new ServiceException("EncodingAESKey 解码后应为 32 字节,实际 " + key.length + " 字节");
        }
        return key;
    }

    private static byte[] pkcs7Pad(byte[] data) {
        int pad = BLOCK_SIZE - (data.length % BLOCK_SIZE);
        if (pad == 0) {
            pad = BLOCK_SIZE;
        }
        byte[] out = new byte[data.length + pad];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) {
            out[i] = (byte) pad;
        }
        return out;
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        if (data.length == 0) {
            throw new ServiceException("企微密文为空");
        }
        int pad = data[data.length - 1] & 0xff;
        if (pad < 1 || pad > BLOCK_SIZE || pad > data.length) {
            throw new ServiceException("企微密文填充非法");
        }
        byte[] out = new byte[data.length - pad];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
