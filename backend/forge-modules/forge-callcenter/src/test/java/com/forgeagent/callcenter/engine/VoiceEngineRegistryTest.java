package com.forgeagent.callcenter.engine;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引擎注册表路由单测(C2):按 code 路由、重复标识拒绝、缺失报已装配清单。 */
@Tag("dev")
class VoiceEngineRegistryTest {

    @Test
    void routesByCode() {
        MockAsrEngine mockAsr = new MockAsrEngine("mock");
        MockTtsEngine mockTts = new MockTtsEngine("mock");
        VoiceEngineRegistry registry = new VoiceEngineRegistry(List.of(mockAsr), List.of(mockTts));
        assertEquals(mockAsr, registry.asr("mock"));
        assertEquals(mockAsr, registry.asr("MOCK"), "路由键大小写不敏感");
        assertEquals(mockTts, registry.tts("mock"));
    }

    @Test
    void missingEngineReportsInstalledOnes() {
        VoiceEngineRegistry registry = new VoiceEngineRegistry(
            List.of(new MockAsrEngine("mock")), List.of(new MockTtsEngine("mock")));
        ServiceException ex = assertThrows(ServiceException.class, () -> registry.asr("dashscope"));
        assertTrue(ex.getMessage().contains("已装配: [mock]"), ex.getMessage());
        assertThrows(ServiceException.class, () -> registry.tts(null));
    }

    @Test
    void duplicateCodeRejected() {
        assertThrows(ServiceException.class, () -> new VoiceEngineRegistry(
            List.of(new MockAsrEngine("mock"), new MockAsrEngine("mock")), List.of()));
        assertThrows(ServiceException.class, () -> new VoiceEngineRegistry(
            List.of(), List.of(new MockTtsEngine("mock"), new MockTtsEngine("mock"))));
    }
}
