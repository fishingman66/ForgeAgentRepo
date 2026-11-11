package com.forgeagent.callcenter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;

/** 渠道验签公共工具(C3):SHA1 字典序签名 + 时间窗防重放。
 *
 * <p>扩展方向:真实 AICC 的呼叫级签名算法(cec_07_0065 核对后)若与 HMAC 口径不同,
 * 在 AiccGateway 内新增算法并保持常量时间比较;本类不承载具体供应商语义。</p> */
public final class CsChannelSignature {

    private CsChannelSignature() {
    }

    /** 微信系签名:token/timestamp/nonce 及可选附加参数按字典序排序后拼接做 SHA1。 */
    public static String sha1(String... parts) {
        String[] sorted = Arrays.copyOf(parts, parts.length);
        Arrays.sort(sorted);
        String joined = String.join("", sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("渠道签名计算失败", e);
        }
    }

    public static boolean withinTolerance(String timestamp, int toleranceSeconds) {
        try {
            long ts = Long.parseLong(timestamp);
            return Math.abs(Instant.now().getEpochSecond() - ts) <= toleranceSeconds;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
