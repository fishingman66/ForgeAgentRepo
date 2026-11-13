package com.forgeagent.callcenter.insight;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** C4:渠道 voice_config_json 的解读口径(未声明 vs 显式关闭必须区分)。 */
@Tag("dev")
class ChannelVoiceSettingsTest {

    private CallcenterProperties properties;
    private ChannelVoiceSettings settings;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new CallcenterProperties();
        properties.setExtractDefaultKeys(List.of("orderNo"));
        properties.setAutoTransferQueue("default_queue");
        ObjectProvider<CsCallChannelConfigMapper> provider = mock(ObjectProvider.class);
        settings = new ChannelVoiceSettings(properties, provider);
    }

    @Test
    void declaredKeysAndQueueOverrideDefaults() {
        ChannelVoiceSettings.Settings parsed = settings.parse(
            "{\"extract_keys\":[\"orderNo\",\"name\"],\"auto_transfer_queue\":\"complaint\"}");
        assertEquals(List.of("orderNo", "name"), parsed.extractKeys());
        assertEquals("complaint", parsed.autoTransferQueue());
    }

    @Test
    void explicitEmptyArrayDisablesExtraction() {
        ChannelVoiceSettings.Settings parsed = settings.parse("{\"extract_keys\":[]}");
        assertTrue(parsed.extractKeys().isEmpty(), "显式空数组=该渠道不抽取,不回落默认");
    }

    @Test
    void missingKeysFallBackToDefaults() {
        ChannelVoiceSettings.Settings parsed = settings.parse("{\"other\":1}");
        assertEquals(List.of("orderNo"), parsed.extractKeys());
        assertEquals("default_queue", parsed.autoTransferQueue());
    }

    @Test
    void explicitEmptyQueueDisablesTransfer() {
        ChannelVoiceSettings.Settings parsed = settings.parse(
            "{\"extract_keys\":[\"amount\"],\"auto_transfer_queue\":\"\"}");
        assertNull(parsed.autoTransferQueue(), "显式空串=该渠道不转接");
    }

    @Test
    void brokenJsonDegradesToDefaults() {
        assertEquals(List.of("orderNo"), settings.parse("不是 JSON").extractKeys());
    }

    @Test
    void blankChannelCodeUsesDefaults() {
        ChannelVoiceSettings.Settings parsed = settings.of("  ");
        assertEquals(List.of("orderNo"), parsed.extractKeys());
        assertEquals("default_queue", parsed.autoTransferQueue());
    }
}
