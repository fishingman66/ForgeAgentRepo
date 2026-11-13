package com.forgeagent.callcenter.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AICC 网关(V1):Mock 契约实现,验签口径与 V0 sequence.json 完全一致
 * (HMAC-SHA256(timestamp + "\n" + nonce + "\n" + rawBody),Base64URL 无填充)。
 *
 * <p>换真实 AICC(V0-real)只改本类 verify/parse/fetchRecording,业务层零改动。</p>
 */
@Slf4j
@Component
@ConditionalOnCallcenterEnabled
public class AiccGateway implements CsVoiceGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONTENT_TYPE = new TypeReference<>() {
    };

    /** 本实现服务的供应商标识:CLOUD 托管 AICC 及其 Mock 共用同一套字段映射(V0~V2 契约)。 */
    @Override
    public boolean supports(String provider) {
        return VoiceProvider.AICC.code().equalsIgnoreCase(provider)
            || VoiceProvider.AICC_MOCK.code().equalsIgnoreCase(provider);
    }

    @Override
    public boolean verify(String secret, String timestamp, String nonce, String rawBody, String signature) {
        // 算法口径与 V0 sequence.json 一致,实现抽取至 CsHmacSignatures(FS 系网关共用)
        return CsHmacSignatures.verify(secret, timestamp, nonce, rawBody, signature);
    }

    @Override
    public List<CsVoiceEvent> parse(String provider, String channelCode, String rawBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("回调报文不是合法 JSON", e);
        }
        JsonNode list = root.path("eventList");
        if (!list.isArray() || list.isEmpty()) {
            return List.of();
        }
        String envelopeWorkNo = root.path("workNo").asText("");
        List<CsVoiceEvent> events = new ArrayList<>();
        for (JsonNode item : list) {
            String rawType = item.path("eventType").asText("");
            String normalized = VoiceProvider.AICC_EVENT_TYPE_MAP.get(rawType);
            if (normalized == null) {
                log.warn("[CC] 未知事件类型 {},跳过(渠道 {})", rawType, channelCode);
                continue;
            }
            Map<String, Object> content = item.hasNonNull("content")
                ? MAPPER.convertValue(item.get("content"), CONTENT_TYPE) : Map.of();
            events.add(new CsVoiceEvent(provider, channelCode, rawType, normalized,
                item.path("workNo").asText(envelopeWorkNo),
                item.path("eventGenTime").asLong(0L), content));
        }
        return events;
    }

    @Override
    public byte[] fetchRecording(String recordingUrl) {
        if (recordingUrl == null || !recordingUrl.startsWith("mock://")) {
            throw new UnsupportedOperationException(
                "真实录音取回属 V0-real 范围,当前仅支持 mock:// 录音地址: " + recordingUrl);
        }
        return mockRecordingBytes();
    }

    /** 8kHz / 16bit / mono / 1 秒静音 WAV,16044 字节,与 V0 fixture 同规格。 */
    public static byte[] mockRecordingBytes() {
        int dataBytes = 16000;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ascii("RIFF")).putInt(36 + dataBytes).put(ascii("WAVE"))
            .put(ascii("fmt ")).putInt(16)
            .putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
            .put(ascii("data")).putInt(dataBytes);
        return buffer.array();
    }

    private static byte[] ascii(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != 4) {
            throw new IllegalStateException("WAV 头字段必须 4 字节: " + value);
        }
        return bytes;
    }
}
