package com.forgeagent.callcenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 事件指纹(V1):V0 契约 sha256(callId|eventType|eventGenTime|canonicalJson(content))。
 *
 * <p>canonicalJson 与 V0 调试端(Python sort_keys + 紧凑分隔符)逐字节对齐,
 * 同一事件在两端算出相同指纹;用于轨迹排障与跨端一致性断言,不做幂等主键。</p>
 */
public final class CallFingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CallFingerprints() {
    }

    public static String of(String callId, String rawType, long eventGenTime, Map<String, Object> content) {
        try {
            Map<String, Object> sorted = content == null ? Map.of() : new TreeMap<>(content);
            String canonical = MAPPER.writeValueAsString(sorted);
            String material = (callId == null ? "" : callId) + "|" + rawType + "|" + eventGenTime + "|" + canonical;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "fp-error";
        }
    }
}
