package com.forgeagent.callcenter.gateway;

import com.forgeagent.callcenter.common.CsChannelSignature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 回调验签公共算法(C1 抽取):HMAC-SHA256(timestamp + "\n" + nonce + "\n" + rawBody),
 * Base64URL 无填充,常量时间比较。
 *
 * <p>V0 以来只有 AICC 一个验签实现;FS(FreeSWITCH)系网关(FsMockGateway/FsGateway)
 * 沿用同一算法(内网共享密钥),抽取为静态工具避免三处复制。
 * 算法口径与 mock/aicc/sequence.json 的 signature 段一致,改动必须同步 fixture。</p>
 */
public final class CsHmacSignatures {

    private CsHmacSignatures() {
    }

    /**
     * 计算期望签名。密钥/时间戳/nonce 为空时返回 null(调用方按验签失败处理)。
     */
    public static String expected(String secret, String timestamp, String nonce, String rawBody) {
        if (secret == null || secret.isBlank() || timestamp == null || nonce == null || rawBody == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = timestamp + "\n" + nonce + "\n" + rawBody;
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 验签:计算期望签名并与传入签名做常量时间比较;任何输入异常一律 false。
     */
    public static boolean verify(String secret, String timestamp, String nonce, String rawBody, String signature) {
        String expected = expected(secret, timestamp, nonce, rawBody);
        return expected != null && CsChannelSignature.constantTimeEquals(expected, signature);
    }
}
