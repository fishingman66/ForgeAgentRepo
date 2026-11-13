package com.forgeagent.callcenter.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道语音配置读取(C4):从 {@code agent_cc_channel_config.voice_config_json}
 * 解析洞察相关声明(extract_keys / auto_transfer_queue),60s TTL 缓存。
 *
 * <p>语义约定("未声明" 与 "声明为空" 必须区分):
 * 字段缺省 → 用 {@code agent.callcenter.extract-default-keys} 兜底;
 * 显式空数组 {@code []} → 该渠道不抽取任何字段(显式关闭)。
 * 自动转接队列同理:字段缺省 → 用全局 {@code auto-transfer-queue};
 * 显式空串 → 该渠道不转接。</p>
 *
 * <p>配置解析失败一律降级为空设置(不抽取、不转接),只记 debug——
 * 洞察是旁路能力,渠道配置写错不能拖累通话主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelVoiceSettings {

    private static final long CACHE_TTL_MS = 60_000L;
    /** 缓存条目上限:渠道号来自外部回调,不能无限增长。 */
    private static final int CACHE_MAX = 256;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CallcenterProperties properties;
    private final ObjectProvider<CsCallChannelConfigMapper> mapperProvider;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 渠道级语音设置:抽取字段 + 自动转接队列 + 是否走「无 IVR 直接应答」分流。 */
    public record Settings(List<String> extractKeys, String autoTransferQueue, boolean directAnswer) {

        /** 兼容旧调用(抽取 + 转接队列),直接应答默认关。 */
        public Settings(List<String> extractKeys, String autoTransferQueue) {
            this(extractKeys, autoTransferQueue, false);
        }

        /** 完全关闭:不抽取、不转接、不分流。 */
        public static final Settings EMPTY = new Settings(List.of(), null, false);
    }

    private record Entry(Settings settings, long loadedAt) {
    }

    /** 读取渠道设置;渠道不存在或配置缺失时回落全局默认。 */
    public Settings of(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return defaults();
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(channelCode);
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MS) {
            return cached.settings();
        }
        Settings settings = load(channelCode);
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(channelCode, new Entry(settings, now));
        return settings;
    }

    /** 清空缓存(渠道配置变更后由管理端或测试调用)。 */
    public void invalidate() {
        cache.clear();
    }

    private Settings defaults() {
        return new Settings(properties.getExtractDefaultKeys() == null
            ? List.of() : properties.getExtractDefaultKeys(),
            properties.getAutoTransferQueue(), false);
    }

    private Settings load(String channelCode) {
        CsCallChannelConfigMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            return defaults();
        }
        CsCallChannelConfig config;
        try {
            config = mapper.selectOne(new LambdaQueryWrapper<CsCallChannelConfig>()
                .eq(CsCallChannelConfig::getChannelCode, channelCode).last("limit 1"));
        } catch (Exception e) {
            log.debug("[CC] 渠道语音配置查询降级 code={}: {}", channelCode, e.getMessage());
            return defaults();
        }
        if (config == null || config.getVoiceConfigJson() == null
            || config.getVoiceConfigJson().isBlank()) {
            return defaults();
        }
        return parse(config.getVoiceConfigJson());
    }

    /** 解析 voice_config_json;只认本类关心的两个键,其余忽略。 */
    Settings parse(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<String> keys;
            if (root.has("extract_keys") && root.get("extract_keys").isArray()) {
                keys = new ArrayList<>();
                for (JsonNode node : root.get("extract_keys")) {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        keys.add(node.asText().trim());
                    }
                }
                // 显式声明(含空数组)即以此为准,不再回落默认
            } else {
                keys = properties.getExtractDefaultKeys() == null
                    ? List.of() : properties.getExtractDefaultKeys();
            }
            String queue;
            if (root.has("auto_transfer_queue")) {
                queue = root.get("auto_transfer_queue").asText("");
                queue = queue.isBlank() ? null : queue.trim();
            } else {
                queue = properties.getAutoTransferQueue();
            }
            boolean directAnswer = root.has("direct_answer") && root.get("direct_answer").asBoolean(false);
            return new Settings(List.copyOf(keys), queue, directAnswer);
        } catch (Exception e) {
            log.debug("[CC] voice_config_json 解析降级: {}", e.getMessage());
            return defaults();
        }
    }
}
