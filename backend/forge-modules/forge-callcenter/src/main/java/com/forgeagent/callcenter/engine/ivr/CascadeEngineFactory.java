package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.engine.CascadeTurnEngine;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.MediaStream;
import com.forgeagent.callcenter.engine.NoInterruptService;
import com.forgeagent.callcenter.engine.VoiceEngineRegistry;
import com.forgeagent.callcenter.engine.TurnListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 级联引擎工厂(C3):为每路通话装配 {@link CascadeTurnEngine} 的会话级资源
 * (MediaStream/ChannelControl 按通话创建,双引擎按配置取单例),
 * 并提供 answer/received 节点需要的阻塞收音与 DTMF 收号原语——
 * SmartCall 的 radio()/received() 语义:IVR 同步循环线程内阻塞等待事件回流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeEngineFactory {

    private final VoiceEngineRegistry registry;
    private final NoInterruptService noInterruptService;

    /** 一通电话的会话级资源束。 */
    public record Session(MediaStream stream, ChannelControl channel, CascadeTurnEngine turnEngine) {
    }

    /**
     * 会话资源(由 IvrSessionDriver 装入 NodeContext):
     * caller 终稿经 latch 回传给阻塞中的 answer 节点;
     * DTMF 队列由事件链路(FsGateway/真实 ESL)投递,C1~C2 Mock 下由测试直喂。
     */
    public static final class CallerChannel {
        private final CascadeTurnEngine turnEngine;
        private final LinkedBlockingDeque<String> dtmfQueue = new LinkedBlockingDeque<>();

        public CallerChannel(CascadeTurnEngine turnEngine) {
            this.turnEngine = turnEngine;
        }

        /**
         * 阻塞收音:先播 prompt(可空),再等 caller 终稿;超时返回 null(静默)。
         * 打断/终稿由 CascadeTurnEngine 内部处理后仅回传终稿文本。
         */
        public String awaitUtterance(String prompt, long timeoutMs) throws InterruptedException {
            AtomicReference<String> finalText = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            if (prompt != null && !prompt.isBlank()) {
                turnEngine.speak(prompt);
            }
            turnEngine.startListening(new TurnListener() {
                @Override
                public void onFinalUtterance(String text, double confidence) {
                    finalText.set(text);
                    latch.countDown();
                }

                @Override
                public void onAsrError(Exception cause) {
                    latch.countDown();
                }
            });
            try {
                latch.await(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            } finally {
                turnEngine.stopListening();
            }
            return finalText.get();
        }

        /** 投递 DTMF 按键(事件链路调用)。 */
        public void offerDtmf(String digit) {
            dtmfQueue.add(digit);
        }

        /** 阻塞收号:收满 maxDigits 或遇到 endKey;超时返回已收序列(可空串)。 */
        public String awaitDtmf(String endKey, int maxDigits, long timeoutMs) throws InterruptedException {
            StringBuilder digits = new StringBuilder();
            long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                String digit = dtmfQueue.poll(100, TimeUnit.MILLISECONDS);
                if (digit == null) {
                    continue;
                }
                if (digit.equals(endKey)) {
                    break;
                }
                digits.append(digit);
                if (digits.length() >= maxDigits) {
                    break;
                }
            }
            return digits.toString();
        }

        public CascadeTurnEngine turnEngine() {
            return turnEngine;
        }
    }

    /** 按通话装配会话引擎(uuid 即 callId;stream/channel 由调用方提供);引擎经 spawn 取独立会话。 */
    public Session create(String uuid, MediaStream stream, ChannelControl channel,
                          String asrCode, String ttsCode) {
        CascadeTurnEngine engine = new CascadeTurnEngine(
            stream, channel,
            registry.asr(asrCode).spawn(), registry.tts(ttsCode).spawn(),
            noInterruptService::matcher);
        return new Session(stream, channel, engine);
    }

}
