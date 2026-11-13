package com.forgeagent.callcenter.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 媒体流 Mock(C1 / V-5):可编程音频注入,让 ASR/TTS/打断逻辑在无 FreeSWITCH 环境
 * 完整测试——测试用 {@code injectCallerAudio}/{@code injectSilence} 模拟 caller
 * 说话与停顿(静默即零字节注入,由消费方按长度判静默),TTS 输出全部落缓冲可断言。
 */
@Component
public class MockMediaStreamProvider implements MediaStreamProvider {

    private final List<MockMediaStream> opened = new ArrayList<>();

    @Override
    public MockMediaStream open(String uuid) {
        for (MockMediaStream stream : opened) {
            if (stream.uuid.equals(uuid)) {
                throw new IllegalStateException("媒体流重复打开: " + uuid);
            }
        }
        MockMediaStream stream = new MockMediaStream(uuid);
        opened.add(stream);
        return stream;
    }

    public MockMediaStream streamOf(String uuid) {
        return opened.stream().filter(s -> s.uuid.equals(uuid)).findFirst()
            .orElseThrow(() -> new IllegalStateException("媒体流未打开: " + uuid));
    }

    /** 单路 Mock 流:上行注入 → listener 回调;下行写入 → 播放队列,isBotPlaying=队列非空。 */
    public static final class MockMediaStream implements MediaStream {

        private final String uuid;
        private Consumer<byte[]> callerListener;
        private final List<byte[]> ttsBuffers = new ArrayList<>();
        private final Deque<byte[]> playbackQueue = new ArrayDeque<>();
        private boolean closed;

        private MockMediaStream(String uuid) {
            this.uuid = uuid;
        }

        @Override
        public void onCallerAudio(Consumer<byte[]> listener) {
            this.callerListener = listener;
        }

        /** 注入 caller 音频帧(测试用):立即回调上行 listener;未注册 listener 时静默丢弃。 */
        public void injectCallerAudio(byte[] pcm8k) {
            if (closed || callerListener == null || pcm8k == null || pcm8k.length == 0) {
                return;
            }
            callerListener.accept(pcm8k);
        }

        /** 注入指定毫秒的静默(8k 16bit 单声道):模拟 caller 停顿,供 VAD/打断逻辑消费。 */
        public void injectSilence(int millis) {
            int bytes = 8000 / 1000 * 2 * millis;
            injectCallerAudio(new byte[bytes]);
        }

        @Override
        public void writeTts(byte[] pcm8k) {
            if (closed) {
                return;
            }
            ttsBuffers.add(pcm8k);
            playbackQueue.add(pcm8k);
        }

        @Override
        public void interruptPlayback() {
            playbackQueue.clear();
        }

        /** 播放器队列非空即为「机器人在说话」;真实侧由 mod_audio_fsc 播放缓冲表达。 */
        @Override
        public boolean isBotPlaying() {
            return !closed && !playbackQueue.isEmpty();
        }

        /**
         * 模拟媒体硬件按序消费播放队列(测试驱动):真实侧由 FreeSWITCH 播放线程消费。
         */
        public void drainPlayback() {
            playbackQueue.clear();
        }

        @Override
        public void close() {
            closed = true;
            ttsBuffers.clear();
            playbackQueue.clear();
        }

        // ---- 断言辅助 ----

        public String uuid() {
            return uuid;
        }

        public boolean isClosed() {
            return closed;
        }

        /** 已写入的全部 TTS 字节(按序拼接)。 */
        public byte[] drainedTtsBytes() {
            int total = ttsBuffers.stream().mapToInt(b -> b.length).sum();
            byte[] all = new byte[total];
            int offset = 0;
            for (byte[] chunk : ttsBuffers) {
                System.arraycopy(chunk, 0, all, offset, chunk.length);
                offset += chunk.length;
            }
            return all;
        }

        public int ttsChunkCount() {
            return ttsBuffers.size();
        }
    }
}
