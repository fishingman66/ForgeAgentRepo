package com.forgeagent.callcenter.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ASR 引擎 Mock(C2 / V-5):脚本化转写——测试把音频帧与转写结果按序编排,
 * feed 到预置字节数后回放下一条脚本(最终稿),无厂商依赖即可驱动
 * CascadeTurnEngine 的完整轮次链路。
 *
 * <p>脚本项:feedBytes 达到阈值时触发 onFinal(text);partial 由
 * partialOnFeed 开关模拟(每帧一次 partial)。故障注入:injectError() 触发 onError。</p>
 */
@Component
public class MockAsrEngine implements AsrEngine {

    /** 一条转写脚本:喂满 feedBytes 字节后回放。 */
    public record Script(int feedBytes, String finalText, double confidence) {
    }

    private final String engineCode;
    private final List<Script> scripts = new ArrayList<>();
    private AsrListener listener;
    private int fedBytes;
    private int scriptCursor;
    private boolean started;
    private Exception pendingError;
    private final List<String> partials = new ArrayList<>();
    /** 第 N 帧的 partial 文本(partialSupplier.apply(帧序号));默认 "partial-N"。 */
    private java.util.function.Function<Integer, String> partialSupplier;

    public MockAsrEngine() {
        this.engineCode = "mock";
    }

    public MockAsrEngine(String engineCode) {
        this.engineCode = engineCode;
    }

    /** 定制 partial 文本(测试装配期调用),用于语气词豁免链路验证。 */
    public MockAsrEngine partialTexts(java.util.function.Function<Integer, String> supplier) {
        this.partialSupplier = supplier;
        return this;
    }

    /** 编排脚本(测试装配期调用)。 */
    public MockAsrEngine addScript(int feedBytes, String finalText, double confidence) {
        scripts.add(new Script(feedBytes, finalText, confidence));
        return this;
    }

    /** 故障注入:下次 feed 触发 onError。 */
    public void injectError(Exception cause) {
        pendingError = cause;
    }

    /** 会话原型:拷贝脚本与 partial 供应商,多路通话互不串扰。 */
    @Override
    public AsrEngine spawn() {
        MockAsrEngine copy = new MockAsrEngine(engineCode);
        copy.scripts.addAll(scripts);
        copy.partialSupplier = partialSupplier;
        return copy;
    }

    @Override
    public String code() {
        return engineCode;
    }

    @Override
    public void start(AsrListener listener) {
        this.listener = listener;
        this.started = true;
        this.fedBytes = 0;
        this.scriptCursor = 0;
        this.partials.clear();
    }

    @Override
    public void feed(byte[] pcm8k) {
        if (!started || listener == null || pcm8k == null) {
            return;
        }
        if (pendingError != null) {
            Exception cause = pendingError;
            pendingError = null;
            listener.onError(cause);
            return;
        }
        fedBytes += pcm8k.length;
        if (listener != null && pcm8k.length > 0) {
            String partialText = partialSupplier != null
                ? partialSupplier.apply(partials.size() + 1) : "partial-" + fedBytes;
            partials.add(partialText);
            listener.onPartial(partialText);
        }
        while (scriptCursor < scripts.size() && fedBytes >= scripts.get(scriptCursor).feedBytes()) {
            Script script = scripts.get(scriptCursor);
            scriptCursor++;
            listener.onFinal(script.finalText(), script.confidence());
        }
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public void close() {
        started = false;
        listener = null;
    }

    public List<String> partials() {
        return List.copyOf(partials);
    }
}
