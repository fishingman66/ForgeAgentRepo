package com.forgeagent.callcenter.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock 引擎行为单测(C2):脚本化 ASR 与手动步进 TTS 的语义是
 * CascadeTurnEngine 全部打断测试的地基,先锁死。
 */
@Tag("dev")
class MockEngineTest {

    @Test
    void asrScriptedFinalsFireByFedBytes() {
        MockAsrEngine asr = new MockAsrEngine("mock")
            .addScript(100, "我要退货", 0.95)
            .addScript(300, "转人工", 0.9);
        List<String> finals = new ArrayList<>();
        asr.start(new AsrListener() {
            @Override
            public void onFinal(String text, double confidence) {
                finals.add(text);
            }

            @Override
            public void onError(Exception cause) {
            }
        });

        asr.feed(new byte[50]);
        assertEquals(0, finals.size(), "未达脚本阈值不回放终稿");
        asr.feed(new byte[60]);
        assertEquals(1, finals.size());
        assertEquals("我要退货", finals.get(0));
        asr.feed(new byte[200]);
        assertEquals(2, finals.size());
        assertEquals("转人工", finals.get(1));
        // 未 start 或 null 帧静默忽略
        asr.stop();
        asr.feed(new byte[10]);
        assertEquals(2, finals.size());
    }

    @Test
    void asrErrorInjectionFiresOnce() {
        MockAsrEngine asr = new MockAsrEngine("mock");
        List<Exception> errors = new ArrayList<>();
        asr.start(new AsrListener() {
            @Override
            public void onFinal(String text, double confidence) {
            }

            @Override
            public void onError(Exception cause) {
                errors.add(cause);
            }
        });
        asr.injectError(new RuntimeException("boom"));
        asr.feed(new byte[10]);
        asr.feed(new byte[10]);
        assertEquals(1, errors.size(), "注入故障只触发一次 onError");
    }

    @Test
    void ttsManualPumpWithLatchAndCancel() {
        MockTtsEngine tts = new MockTtsEngine("mock");
        List<Integer> fired = new ArrayList<>();
        List<byte[]> sink = new ArrayList<>();
        tts.synthesize("你好", () -> fired.add(1), sink::add);
        assertEquals(4, tts.queuedChunks(), "2 字文本 × 2 = 4 块");
        assertFalse(tts.isLatchFired(), "pump 前 latch 不触发");

        tts.pump(1);
        assertTrue(tts.isLatchFired());
        assertEquals(1, sink.size());

        tts.cancel();
        tts.pump(10);
        assertEquals(1, sink.size(), "cancel 后 pump 不再释放块");

        tts.resetCancel();
        tts.synthesize("第二轮", () -> fired.add(2), sink::add);
        tts.pumpAll();
        // 第二轮 3 字 × 2 = 6 块;第一轮已收 1 块,共 7
        assertEquals(7, sink.size());
        assertEquals(2, fired.size(), "第二轮首包 latch 再次触发");
    }
}
