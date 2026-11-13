package com.forgeagent.callcenter.engine;

import java.util.function.Consumer;

/**
 * 媒体流接口(C1 / 方案 docs/07 §三.4):一路通话的双工音频管道。
 *
 * <p>上行 = caller 音频(喂 ASR);下行 = TTS 合成音频(播放)。C2 级联链路与
 * C7 实时模型共用;实现两态:{@code FsMediaStreamProvider}(C1-real,
 * mod_audio_fsc WebSocket)与 {@code MockMediaStreamProvider}(C1,可编程音频注入)。</p>
 *
 * <p>音频规格:8kHz / 16bit / mono PCM(电话口径);采样率协商是 C7 实时模型
 * 适配层的事,本接口固定 8k,避免过早抽象。</p>
 */
public interface MediaStreamProvider {

    /** 打开一路媒体流;同一 uuid 重复打开由实现决定(真实侧幂等,Mock 抛异常暴露调用错误)。 */
    MediaStream open(String uuid);
}
