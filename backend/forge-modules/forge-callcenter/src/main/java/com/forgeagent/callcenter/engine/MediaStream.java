package com.forgeagent.callcenter.engine;

import java.util.function.Consumer;

/**
 * 一路双工媒体流。命名约定:caller 音频上行(→ASR),TTS 音频下行(播放,可打断)。
 */
public interface MediaStream extends AutoCloseable {

    /** 注册 caller 上行音频消费者(喂 ASR);重复注册由实现决定(建议覆盖)。 */
    void onCallerAudio(Consumer<byte[]> listener);

    /** 下行写 TTS 合成音频(8k/16bit/mono PCM),由实现播放。 */
    void writeTts(byte[] pcm8k);

    /** 打断:清空下行播放队列;之后 {@link #isBotPlaying()} 应为 false。 */
    void interruptPlayback();

    /** 当前是否在播放 TTS 音频(区分真实音频帧与补静音帧,避免误判播完)。 */
    boolean isBotPlaying();

    @Override
    void close();
}
