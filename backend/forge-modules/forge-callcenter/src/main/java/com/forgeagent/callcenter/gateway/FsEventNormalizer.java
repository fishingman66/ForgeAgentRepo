package com.forgeagent.callcenter.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FreeSWITCH ESL 事件归一化器(C1):把 ESL 风格事件(事件名 + name-value 头)映射为
 * 归一化 {@link CsVoiceEvent},映射口径与 mock/fs/sequence.json 的 eventTypeMap 一致。
 *
 * <p>设计意图(FS_MOCK V-5):本类是 FsMockGateway 与未来 FsGateway(C1-real)共用的
 * 唯一映射表——Mock 从 fixture JSON 读入 ESL 事件,真实实现从 ESL 连接读入,
 * 归一化层零差异,C1-real 只换数据源不换映射。事件名改动必须同步 fixture。</p>
 *
 * <p>映射口径(FS_MOCK_V1 契约):
 * callId = Unique-ID;caller = Caller-Caller-ID-Number;callee = Caller-Destination-Number;
 * eventGenTime = Event-Date-Timestamp(秒 epoch,乘 1000 转毫秒);
 * 转写/机器人话术/转人工/坐席状态/录音就绪/话单在 ESL 中无原生事件,
 * 用 CUSTOM 事件 subClass 区分(forgeagent::asr_result 等)——真实链路中这些
 * 是媒体管道与业务层产生的内部事件,Mock 下由 fixture 注入,契约一致。</p>
 */
public final class FsEventNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONTENT_TYPE = new TypeReference<>() {
    };

    /** ESL 事件名(含 CUSTOM subClass) -> 归一化事件名;与 mock/fs/sequence.json eventTypeMap 一致。 */
    public static final Map<String, String> FS_EVENT_TYPE_MAP = Map.ofEntries(
        Map.entry("CHANNEL_CREATE", "call.ring"),
        Map.entry("CHANNEL_ANSWER", "call.answer"),
        Map.entry("forgeagent::asr_result", "call.asr_result"),
        Map.entry("forgeagent::bot_utterance", "call.bot_utterance"),
        Map.entry("DTMF", "call.dtmf"),
        Map.entry("forgeagent::transfer", "call.transfer"),
        Map.entry("callcenter::agent_state", "call.seat_state"),
        Map.entry("forgeagent::record_ready", "call.record.ready"),
        Map.entry("CHANNEL_HANGUP_COMPLETE", "call.hangup"),
        Map.entry("forgeagent::cdr", "call.cdr"));

    private FsEventNormalizer() {
    }

    /**
     * 解析 FS_MOCK 回调报文:{"events": [{"eslEventName": "...", "eventDateTimestampSec": 0,
     * "workNo": "", "headers": {...}}]}。未知事件名跳过(记 warn 语义,返回值体现),不抛异常。
     */
    public static List<CsVoiceEvent> parse(String provider, String channelCode, String rawBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("回调报文不是合法 JSON", e);
        }
        JsonNode list = root.path("events");
        if (!list.isArray() || list.isEmpty()) {
            return List.of();
        }
        List<CsVoiceEvent> events = new ArrayList<>();
        for (JsonNode item : list) {
            String eslName = item.path("eslEventName").asText("");
            String normalized = FS_EVENT_TYPE_MAP.get(eslName);
            if (normalized == null) {
                continue;
            }
            Map<String, Object> headers = item.hasNonNull("headers")
                ? MAPPER.convertValue(item.get("headers"), CONTENT_TYPE) : new LinkedHashMap<>();
            Map<String, Object> content = toContent(eslName, normalized, headers);
            events.add(new CsVoiceEvent(provider, channelCode, eslName, normalized,
                item.path("workNo").asText(String.valueOf(headers.getOrDefault("variable_workNo", ""))),
                eventGenTimeMs(item, headers), content));
        }
        return events;
    }

    /** ESL 时间戳口径:Event-Date-Timestamp 为秒 epoch;fixture 亦可用顶层 eventGenTimeMs 直接给毫秒。 */
    private static long eventGenTimeMs(JsonNode item, Map<String, Object> headers) {
        long direct = item.path("eventGenTimeMs").asLong(0L);
        if (direct > 0) {
            return direct;
        }
        Object ts = headers.get("Event-Date-Timestamp");
        if (ts instanceof Number number) {
            // ESL 真实推给的是微秒字符串的场景由字符串路径兜底,数值按秒处理
            long value = number.longValue();
            return value < 10_000_000_000L ? value * 1000 : value;
        }
        try {
            long value = Long.parseLong(String.valueOf(ts));
            return value < 10_000_000_000L ? value * 1000 : value;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * ESL 头 -> content 字段映射:业务层消费的键名(callId/caller/callee/...)与 AICC 契约保持一致,
     * 使状态机/幂等/转写入库的业务断言在 AICC 与 FS 两条链路间完全共用。
     */
    private static Map<String, Object> toContent(String eslName, String normalized, Map<String, Object> headers) {
        Map<String, Object> content = new LinkedHashMap<>();
        switch (normalized) {
            case "call.ring" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                content.put("caller", headers.getOrDefault("Caller-Caller-ID-Number", ""));
                content.put("callee", headers.getOrDefault("Caller-Destination-Number", ""));
                content.put("ani", headers.getOrDefault("Caller-Caller-ID-Number", ""));
                content.put("dnis", headers.getOrDefault("Caller-Destination-Number", ""));
                content.put("direction", "inbound");
            }
            case "call.answer", "call.hangup" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                if ("call.hangup".equals(normalized)) {
                    content.put("hangupCause", headers.getOrDefault("Hangup-Cause", ""));
                    content.put("billSec", headers.getOrDefault("variable_billsec", "0"));
                }
            }
            case "call.asr_result", "call.bot_utterance" -> {
                // 转写/话术事件的业务字段由内部管道产生,fixture 直接放 headers 同名键
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                copyIfPresent(headers, content, "speaker", "text", "confidence", "isFinal", "startMs", "endMs");
            }
            case "call.dtmf" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                content.put("digits", headers.getOrDefault("DTMF-Digit", ""));
            }
            case "call.transfer" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                content.put("transferFrom", headers.getOrDefault("variable_transferFrom", ""));
                content.put("transferTo", headers.getOrDefault("CC-Queue", ""));
            }
            case "call.seat_state" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                content.put("workNo", headers.getOrDefault("CC-Agent", ""));
                content.put("state", headers.getOrDefault("CC-Agent-State", ""));
            }
            case "call.record.ready" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                content.put("recordingId", headers.getOrDefault("variable_recordRecordingId", ""));
                // 键名对齐 AICC 契约(recordingUrl),RecordingIngestService 业务断言两链路共用
                content.put("recordingUrl", headers.getOrDefault("variable_recordFilePath", ""));
            }
            case "call.cdr" -> {
                content.put("callId", headers.getOrDefault("Unique-ID", ""));
                copyIfPresent(headers, content, "durationSec", "billSec", "hangupCause", "hangupDisposition");
            }
            default -> content.put("callId", headers.getOrDefault("Unique-ID", ""));
        }
        return content;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) {
            Object value = from.get(key);
            if (value != null) {
                to.put(key, value);
            }
        }
    }
}
