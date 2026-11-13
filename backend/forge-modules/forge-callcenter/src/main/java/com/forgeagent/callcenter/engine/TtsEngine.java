package com.forgeagent.callcenter.engine;

import java.util.function.Consumer;

/**
 * TTS 引擎 SPI(C2 / 方案 docs/07 P0-1①):文本 → 8kHz/16bit/mono PCM 流式合成。
 *
 * <p>两阶段回调语义对齐 SmartCall TtsClient 的首包放行经验:
 * firstAudioLatch 在第一段音频就绪时执行一次(下游此时才开始向媒体管道投放,
 * 首响延迟 = 用户感知的第一声),audioSink 按序接收后续合成块;
 * {@link #cancel()} 供打断(barge-in)使用,清空内部合成队列。</p>
 */
public interface TtsEngine extends AutoCloseable {

    /** 引擎标识,与注册表路由键一致。 */
    String code();

    /** 会话原型(C3):语义同 AsrEngine.spawn()。 */
    default TtsEngine spawn() {
        return this;
    }

    /** 输出采样率,C2 电话口径固定 8000;非 8k 引擎由实现自行重采样,不外泄复杂度。 */
    int sampleRate();

    /**
     * 流式合成:audioSink 按序回调合成块;firstAudioLatch 在首块回调前执行一次。
     * 同一引擎实例允许串行多次调用(排队合成);cancel 后未合成的文本静默丢弃。
     */
    void synthesize(String text, Runnable firstAudioLatch, Consumer<byte[]> audioSink);

    /** 打断:丢弃全部未合成/未回调的音频。 */
    void cancel();

    @Override
    void close();
}
