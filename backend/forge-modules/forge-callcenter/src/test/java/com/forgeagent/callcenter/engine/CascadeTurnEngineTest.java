package com.forgeagent.callcenter.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 端到端轮次测试:Mock 媒体管道 + Mock 通道 + Mock 双引擎 +
 * CascadeTurnEngine,完整走通「播报→打断→收音→终稿」与全部豁免/故障分支。
 * 这就是方案 C2 验证口径「MockMediaStreamProvider 音频注入端到端」的主体。
 */
@Tag("dev")
class CascadeTurnEngineTest {

    /** 组装一条完整级联链路,返回各组件句柄。 */
    private record Rig(MockMediaStreamProvider.MockMediaStream stream,
                       MockChannelControl channel,
                       MockAsrEngine asr,
                       MockTtsEngine tts,
                       CascadeTurnEngine engine,
                       List<String> finals,
                       List<String> bargeIns,
                       List<Exception> asrErrors) {
    }

    private Rig rig(List<String> noInterruptWords) {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        MockMediaStreamProvider.MockMediaStream stream = provider.open("FS-CALL-1");
        MockChannelControl channel = new MockChannelControl("FS-CALL-1", 0);
        MockAsrEngine asr = new MockAsrEngine("mock")
            .addScript(1600, "太慢了转人工吧", 0.9);
        MockTtsEngine tts = new MockTtsEngine("mock");
        List<String> finals = new ArrayList<>();
        List<String> bargeIns = new ArrayList<>();
        List<Exception> asrErrors = new ArrayList<>();
        CascadeTurnEngine engine = new CascadeTurnEngine(stream, channel, asr, tts,
            () -> new NoInterruptMatcher(noInterruptWords, List.of()));
        engine.startListening(new TurnListener() {
            @Override
            public void onFinalUtterance(String text, double confidence) {
                finals.add(text);
            }

            @Override
            public void onBargeIn(String partial) {
                bargeIns.add(partial);
            }

            @Override
            public void onAsrError(Exception cause) {
                asrErrors.add(cause);
            }
        });
        return new Rig(stream, channel, asr, tts, engine, finals, bargeIns, asrErrors);
    }

    @Test
    void speakQueuesAndPumpDeliversToStream() {
        Rig rig = rig(List.of());
        rig.engine().speak("您好,请问有什么可以帮您");
        assertEquals(0, rig.tts().isLatchFired() ? 1 : 0);
        rig.tts().pump(2);
        assertTrue(rig.tts().isLatchFired(), "首块 pump 触发首包 latch");
        assertTrue(rig.stream().isBotPlaying(), "首块写入后媒体管道进入播放态");
        rig.tts().pumpAll();
        rig.stream().drainPlayback();
        assertFalse(rig.stream().isBotPlaying());
        assertTrue(rig.tts().synthesizedTexts().contains("您好,请问有什么可以帮您"));
    }

    @Test
    void bargeInInterruptsPlayback() {
        Rig rig = rig(List.of());
        rig.engine().speak("长长的话术...");
        rig.tts().pump(1);
        assertTrue(rig.stream().isBotPlaying());

        // caller 开口(非语气词)→ 三路齐断 + onBargeIn
        rig.stream().injectCallerAudio(new byte[100]);
        assertFalse(rig.stream().isBotPlaying(), "媒体队列已清");
        assertTrue(rig.tts().isCancelled(), "TTS 合成已取消");
        assertEquals(1, rig.bargeIns().size());
        // 打断后剩余块不再投放
        rig.tts().pumpAll();
        assertFalse(rig.stream().isBotPlaying());
    }

    @Test
    void noInterruptWordDoesNotTriggerBargeIn() {
        // partial 文本可编程:第 1 帧语气词「嗯」,第 2 帧真实诉求
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        MockMediaStreamProvider.MockMediaStream stream = provider.open("FS-CALL-2");
        MockChannelControl channel = new MockChannelControl("FS-CALL-2", 0);
        MockAsrEngine asr = new MockAsrEngine("mock")
            .partialTexts(frame -> frame == 1 ? "嗯" : "我要退货");
        MockTtsEngine tts = new MockTtsEngine("mock");
        List<String> bargeIns = new ArrayList<>();
        CascadeTurnEngine engine = new CascadeTurnEngine(stream, channel, asr, tts,
            () -> new NoInterruptMatcher(List.of("嗯", "好的"), List.of()));
        engine.startListening(new TurnListener() {
            @Override
            public void onFinalUtterance(String text, double confidence) {
            }

            @Override
            public void onBargeIn(String partial) {
                bargeIns.add(partial);
            }
        });

        engine.speak("话术");
        tts.pump(1);
        assertTrue(stream.isBotPlaying());

        // 第 1 帧:partial=「嗯」命中精确词表 → 不打断
        stream.injectCallerAudio(new byte[100]);
        assertTrue(stream.isBotPlaying(), "语气词豁免,播放继续");
        assertEquals(0, bargeIns.size());

        // 第 2 帧:partial=「我要退货」不在词表 → 三路齐断
        stream.injectCallerAudio(new byte[100]);
        assertFalse(stream.isBotPlaying());
        assertTrue(tts.isCancelled());
        assertEquals(1, bargeIns.size());
        assertEquals("我要退货", bargeIns.get(0));
    }

    @Test
    void finalUtteranceAfterSilenceScript() {
        Rig rig = rig(List.of());
        rig.tts().pumpAll();
        // 喂满脚本阈值 1600 字节 → onFinal
        rig.stream().injectCallerAudio(new byte[1600]);
        assertEquals(1, rig.finals().size());
        assertEquals("太慢了转人工吧", rig.finals().get(0));
    }

    @Test
    void asrErrorTerminatesTurnGracefully() {
        Rig rig = rig(List.of());
        rig.asr().injectError(new RuntimeException("quota"));
        rig.stream().injectCallerAudio(new byte[100]);
        assertEquals(1, rig.asrErrors().size());
        // 后续帧不再喂入(轮次终止),无二次错误
        rig.stream().injectCallerAudio(new byte[100]);
        assertEquals(1, rig.asrErrors().size());
        assertEquals(0, rig.finals().size());
    }

    @Test
    void closeIsIdempotentAndBlocksFurtherUse() {
        Rig rig = rig(List.of());
        rig.engine().close();
        rig.engine().close();
        assertThrows(IllegalStateException.class, () -> rig.engine().speak("x"));
    }
}
