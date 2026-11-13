package com.forgeagent.callcenter.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * TTS 引擎 Mock(C2 / V-5):**手动步进式**回放——synthesize 只把块排入队列,
 * 由测试驱动 {@code pump(n)} 逐块释放(首包 latch 在第一块前触发),
 * 才能真实测到「播放到一半被打断」的时序;cancel 清空队列,后续 pump 无效。
 *
 * <p>块大小 160 字节 = 10ms @ 8kHz/16bit/mono,与真实媒体管道节拍同口径。
 * 同一实例支持多轮 synthesize(新一轮覆盖上一轮的 sink 与 latch)。</p>
 */
@Component
public class MockTtsEngine implements TtsEngine {

    /** 每块 PCM 字节数:8000Hz × 2 字节 × 0.01s = 160。 */
    public static final int CHUNK_BYTES = 160;

    private final String engineCode;
    private final Deque<byte[]> queue = new ArrayDeque<>();
    private final List<String> synthesizedTexts = new ArrayList<>();
    private Consumer<byte[]> sink;
    private Runnable pendingLatch;
    private boolean latchFired;
    private boolean cancelled;
    /** 自动流式模式:true 时 synthesize 内部同步 pumpAll(模拟 WS 回调线程持续投喂);默认 false 手动步进。 */
    private boolean autoPump;

    /** 测试开关:自动流式模式(端到端用);打断时序测试保持手动步进。 */
    public MockTtsEngine setAutoPump(boolean autoPump) {
        this.autoPump = autoPump;
        return this;
    }

    public MockTtsEngine() {
        this.engineCode = "mock";
    }

    public MockTtsEngine(String engineCode) {
        this.engineCode = engineCode;
    }

    /** 会话原型:新实例(队列/取消标记天然隔离)。 */
    @Override
    public TtsEngine spawn() {
        return new MockTtsEngine(engineCode);
    }

    @Override
    public String code() {
        return engineCode;
    }

    @Override
    public int sampleRate() {
        return 8000;
    }

    @Override
    public void synthesize(String text, Runnable firstAudioLatch, Consumer<byte[]> audioSink) {
        // cancel 只作用于在途批次;新批次自动恢复(bargeIn 后的下一轮播报必须可发声)
        cancelled = false;
        synthesizedTexts.add(text);
        sink = audioSink;
        pendingLatch = firstAudioLatch;
        latchFired = false;
        int chunks = Math.max(1, text.length() * 2);
        for (int i = 0; i < chunks; i++) {
            queue.add(new byte[CHUNK_BYTES]);
        }
        if (autoPump) {
            pumpAll();
        }
    }

    /** 释放 n 块到最近一次 synthesize 的 sink;首块前触发 firstAudioLatch。 */
    public void pump(int n) {
        for (int i = 0; i < n && !queue.isEmpty(); i++) {
            if (!latchFired && pendingLatch != null) {
                latchFired = true;
                pendingLatch.run();
            }
            byte[] chunk = queue.poll();
            if (cancelled) {
                return;
            }
            if (sink != null) {
                sink.accept(chunk);
            }
        }
    }

    /** 释放全部剩余块。 */
    public void pumpAll() {
        pump(Integer.MAX_VALUE);
    }

    @Override
    public void cancel() {
        cancelled = true;
        queue.clear();
    }

    /** 测试用:复位取消标记,支持同一实例多轮合成。 */
    public void resetCancel() {
        cancelled = false;
    }

    @Override
    public void close() {
        cancelled = true;
        queue.clear();
    }

    // ---- 断言辅助 ----

    public int queuedChunks() {
        return queue.size();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isLatchFired() {
        return latchFired;
    }

    public List<String> synthesizedTexts() {
        return List.copyOf(synthesizedTexts);
    }
}
