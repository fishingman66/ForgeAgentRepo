package com.forgeagent.callcenter.config;

import com.forgeagent.callcenter.engine.MockAsrEngine;
import com.forgeagent.callcenter.engine.MockTtsEngine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发剧本装配(P3,dev-script):给 mock 引擎预置一段默认对话,让**无 API key 也能
 * 真机拨测全链路**——caller 说任何话,ASR 按"喂满字节数"回放脚本文本,
 * TTS 自动步进出"声音"(字节流动真实,只是没有真实语音)。
 *
 * <p>覆盖范围:事件轨迹、IVR 流程推进、打断判定、转写落库、转人工、坐席推荐、指标——
 * 全部真实;唯一假的是「识别出的内容」(固定脚本)。切真实引擎只需
 * {@code asr-engine=dashscope} + API key,本类随即静默(asr-engine≠mock 时不该用剧本)。</p>
 *
 * <p>脚本节奏(8kHz/16bit ≈ 16KB/s):caller 持续说话约 1 秒出第一句终稿,
 * 约 3 秒出第二句;partial 默认 "partial-N" 非空,播放中说话会触发 barge-in——
 * 这正是要真机验证的行为。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.callcenter", name = "dev-script", havingValue = "true")
public class DevVoiceScriptConfig {

    private final MockAsrEngine mockAsrEngine;
    private final MockTtsEngine mockTtsEngine;

    @PostConstruct
    public void install() {
        // 8k/16bit:16000 字节 ≈ 1 秒通话音频
        mockAsrEngine.addScript(16000, "我要查一下订单状态", 0.93);
        mockAsrEngine.addScript(48000, "我要办理退货", 0.9);
        mockTtsEngine.setAutoPump(true);
        log.warn("[CC-DEV] 开发剧本已启用:mock ASR 预置 2 段脚本 + TTS 自动步进;"
            + "仅限联调,识别内容为固定文本。生产请关闭 dev-script");
    }
}
