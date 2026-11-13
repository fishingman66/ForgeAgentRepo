package com.forgeagent.callcenter.engine;

/**
 * ASR 引擎 SPI(C2 / 方案 docs/07 P0-1①):消费 caller 上行音频帧,产出流式转写。
 *
 * <p>接口形态按 FS_MOCK 双工管道设计(对齐 {@link MediaStream} 的回调式),
 * 不照抄 SmartCall 的 PipedInputStream 阻塞签名——阻塞流在我们的编排里
 * 没有消费者线程可用,回调式让 CascadeTurnEngine 单线程驱动成为可能。
 * 厂商实现(如 DashScope realtime)在回调内自行处理 WS 推送,差异隔离在实现内。</p>
 *
 * <p>实现清单:MockAsrEngine(C1~C2 测试)/ DashScopeAsrEngine(C2-real,需 API key)。
 * 生命周期:start → feed*N → stop → close;任意顺序异常由实现保证幂等。</p>
 */
public interface AsrEngine extends AutoCloseable {

    /** 引擎标识,与注册表路由键一致(如 mock/dashscope)。 */
    String code();

    /**
     * 会话原型(C3):注册表持有单例 Bean,每路通话经 spawn 取独立会话实例——
     * 有状态引擎(脚本/连接)必须覆写;无状态引擎默认返回 this。
     */
    default AsrEngine spawn() {
        return this;
    }

    /** 开始一轮收音:重置内部状态,后续结果经 listener 回调。 */
    void start(AsrListener listener);

    /** 喂入 caller 音频帧(8kHz/16bit/mono PCM)。start 之前调用实现应忽略。 */
    void feed(byte[] pcm8k);

    /** 结束收音:刷出终稿(onFinal)后回到空闲;可再次 start 复用。 */
    void stop();

    @Override
    void close();
}
