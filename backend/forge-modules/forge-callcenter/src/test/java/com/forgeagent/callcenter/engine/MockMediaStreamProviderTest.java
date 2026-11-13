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
 * 媒体流 Mock 单测(C1):上行注入 → listener 回调、TTS 缓冲拼接、打断清缓冲、
 * 静默注入字节数口径(8kHz/16bit/mono = 16 bytes/ms)。
 */
@Tag("dev")
class MockMediaStreamProviderTest {

    @Test
    void callerAudioInjectedToListener() {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        MockMediaStreamProvider.MockMediaStream stream = provider.open("FS-CALL-1");
        List<byte[]> received = new ArrayList<>();
        stream.onCallerAudio(received::add);

        stream.injectCallerAudio(new byte[]{1, 2, 3});
        stream.injectCallerAudio(new byte[]{4, 5});
        assertEquals(2, received.size());
        assertEquals(3, received.get(0).length);
        assertEquals(2, received.get(1).length);
    }

    @Test
    void silenceInjectionFollowsTelephoneRate() {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        MockMediaStreamProvider.MockMediaStream stream = provider.open("FS-CALL-1");
        List<byte[]> received = new ArrayList<>();
        stream.onCallerAudio(received::add);
        // 100ms 静默 = 8000/1000 * 2 * 100 = 1600 字节
        stream.injectSilence(100);
        assertEquals(1, received.size());
        assertEquals(1600, received.get(0).length);
        // 静默即零填充
        for (byte b : received.get(0)) {
            assertEquals(0, b);
        }
    }

    @Test
    void ttsBufferedAndInterruptClears() {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        MockMediaStreamProvider.MockMediaStream stream = provider.open("FS-CALL-1");
        stream.writeTts(new byte[]{1, 1, 1});
        stream.writeTts(new byte[]{2, 2});
        assertTrue(stream.isBotPlaying());
        assertEquals(2, stream.ttsChunkCount());
        assertEquals(5, stream.drainedTtsBytes().length);
        assertEquals(1, stream.drainedTtsBytes()[0]);
        assertEquals(2, stream.drainedTtsBytes()[4]);

        stream.interruptPlayback();
        // 打断清的是播放队列(isBotPlaying 复位);ttsBuffers 是已写入的历史记录,保留供审计
        assertFalse(stream.isBotPlaying());
        assertEquals(2, stream.ttsChunkCount());
        assertEquals(5, stream.drainedTtsBytes().length);
        // drainPlayback(模拟硬件消费)同样只清播放队列
        stream.drainPlayback();
        assertFalse(stream.isBotPlaying());
        assertEquals(5, stream.drainedTtsBytes().length);
    }

    @Test
    void duplicateOpenRejectedAndClosedStreamIgnoresInput() {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        provider.open("FS-CALL-1");
        assertThrows(IllegalStateException.class, () -> provider.open("FS-CALL-1"));

        MockMediaStreamProvider.MockMediaStream stream = provider.streamOf("FS-CALL-1");
        List<byte[]> received = new ArrayList<>();
        stream.onCallerAudio(received::add);
        stream.close();
        stream.injectCallerAudio(new byte[]{1});
        stream.writeTts(new byte[]{1});
        assertTrue(stream.isClosed());
        assertFalse(stream.isBotPlaying());
        assertEquals(0, received.size());
    }

    @Test
    void streamOfRejectsUnknownUuid() {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        assertThrows(IllegalStateException.class, () -> provider.streamOf("not-opened"));
    }
}
