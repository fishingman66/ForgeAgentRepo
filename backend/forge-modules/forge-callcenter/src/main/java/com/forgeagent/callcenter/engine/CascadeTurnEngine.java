package com.forgeagent.callcenter.engine;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 级联轮次编排引擎(C2 / 方案 docs/07 P0-1①):一条通话内的对话轮次驱动——
 * 话术播报(TTS→媒体管道)、收音(媒体管道→ASR)、barge-in 打断、no-interrupt 豁免。
 *
 * <p>打断语义(对齐 SmartCall radio()):播放中收到 caller 音频且 ASR partial
 * 非空、且未命中不允许打断词组 → 三路齐断(媒体管道清队列 + TTS cancel +
 * 本地播放状态复位),并回调 onBargeIn;命中语气词(嗯/哦/好的)不触发。</p>
 *
 * <p>线程契约:与 SmartCall 控制面单线程一致,所有方法由同一驱动线程调用
 * (C3 为 IVR 节点循环线程,真实媒体回调经事件队列汇入),本类不做并发防护。</p>
 */
@Slf4j
public class CascadeTurnEngine implements AutoCloseable {

    private final MediaStream stream;
    private final ChannelControl channel;
    private final AsrEngine asr;
    private final TtsEngine tts;
    private final Supplier<NoInterruptMatcher> noInterruptSupplier;

    /** 收音态:listener 注册与 ASR 生命周期跟随该状态。 */
    private boolean listening;
    private boolean closed;
    private TurnListener turnListener;

    public CascadeTurnEngine(MediaStream stream, ChannelControl channel,
                             AsrEngine asr, TtsEngine tts,
                             Supplier<NoInterruptMatcher> noInterruptSupplier) {
        this.stream = stream;
        this.channel = channel;
        this.asr = asr;
        this.tts = tts;
        this.noInterruptSupplier = noInterruptSupplier;
    }

    /**
     * 话术播报:流式合成,首块经媒体管道投放(首响),后续块顺序播放。
     * 播放完成由媒体管道回放状态表达(isBotPlaying=false),本方法不阻塞等待。
     */
    public void speak(String text) {
        ensureOpen();
        tts.synthesize(text,
            () -> { /* 首包放行:媒体管道在首个 writeTts 时自然开始播放,无需额外动作 */ },
            stream::writeTts);
    }

    /** 打断当前播报:三路齐断(媒体队列 + TTS 合成 + 播放状态)。 */
    public void interruptPlayback() {
        tts.cancel();
        stream.interruptPlayback();
    }

    /**
     * 开始收音:caller 上行音频喂 ASR;partial 触发打断判定,终稿回调监听器。
     * 重复调用幂等(已是收音态时仅刷新监听器)。
     */
    public void startListening(TurnListener listener) {
        ensureOpen();
        this.turnListener = listener;
        if (listening) {
            return;
        }
        listening = true;
        stream.onCallerAudio(this::onCallerFrame);
        asr.start(new AsrListener() {
            @Override
            public void onPartial(String text) {
                if (!listening || turnListener == null) {
                    return;
                }
                judgeBargeIn(text);
            }

            @Override
            public void onFinal(String text, double confidence) {
                if (!listening || turnListener == null) {
                    return;
                }
                turnListener.onFinalUtterance(text, confidence);
            }

            @Override
            public void onError(Exception cause) {
                // 故障降级:轮次终止(不再喂帧),链路不崩;上层可重试 startListening 或转人工
                log.warn("[CC] ASR 引擎故障,收音轮次终止: {}", cause.getMessage());
                listening = false;
                if (turnListener != null) {
                    turnListener.onAsrError(cause);
                }
            }
        });
    }

    /** 结束收音(保留连接,可再次 startListening)。 */
    public void stopListening() {
        listening = false;
        asr.stop();
    }

    private void onCallerFrame(byte[] pcm8k) {
        if (listening) {
            asr.feed(pcm8k);
        }
    }

    /** 打断判定三条件:播放中 + partial 非空 + 未命中不打断词组。 */
    private void judgeBargeIn(String partial) {
        if (!stream.isBotPlaying() || partial == null || partial.isBlank()) {
            return;
        }
        if (noInterruptSupplier.get().shouldSkip(partial)) {
            return;
        }
        interruptPlayback();
        if (turnListener != null) {
            turnListener.onBargeIn(partial);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("CascadeTurnEngine 已关闭");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        listening = false;
        tts.close();
        asr.close();
        stream.close();
    }
}
