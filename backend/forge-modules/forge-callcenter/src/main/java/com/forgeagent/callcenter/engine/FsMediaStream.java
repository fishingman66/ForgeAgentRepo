package com.forgeagent.callcenter.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * FreeSWITCH 真实媒体流(C1-real):一路 mod_audio_stream WebSocket 会话的 Java 侧句柄。
 *
 * <p>协议(mod_audio_stream,已核实):
 * <ul>
 *   <li>上行:FreeSWITCH 主动连本服务,以二进制帧推 PCM16LE(8k/16k,mono),无容器头</li>
 *   <li>下行:TTS 音频需包成 JSON {@code {"type":"streamAudio","data":{...}}} 回推,
 *       {@code audioData} 为 Base64;raw 类型直接注入通话</li>
 *   <li>打断:文本帧 {@code {"type":"clearAudio"}} 清空已排队的下行音频</li>
 * </ul></p>
 *
 * <p>时序:connect 与 {@link MediaStreamProvider#open} 谁先到不确定,本类以
 * {@link #attach}/{@link #detach} 解耦——未连上时下行音频先缓冲,连上后补发。</p>
 *
 * <p>{@link #isBotPlaying} 是本地估算:FreeSWITCH 消费播放队列的进度无法回读,
 * 按「已写字节 / 8k*2 字节每秒」推算播完时刻,误差在一个 write 块内,
 * 仅用于 barge-in 判定,不用于精确计时。</p>
 */
@Slf4j
public class FsMediaStream implements MediaStream {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 8kHz / 16bit / mono 的字节率。 */
    private static final int BYTES_PER_SECOND = 8000 * 2;

    private final String callId;
    /** 下行/上行通道抽象;未连接时为 null,连接后由 Hub 注入。 */
    private volatile AudioSocket socket;
    private Consumer<byte[]> callerListener;
    private volatile boolean closed;

    /** 已写出但按估算仍在播放的下行音频截止时刻(ms)。 */
    private final AtomicLong playingUntilMs = new AtomicLong(0);
    /** 连接建立前缓冲的 TTS 块(按序),连上后一次性补发。 */
    private final java.util.List<byte[]> pending = new java.util.ArrayList<>();

    public FsMediaStream(String callId) {
        this.callId = callId;
    }

    /**
     * 上行音频通道:供 Hub 在收到二进制帧时调用,转交 ASR。
     */
    public void onCallerFrame(byte[] pcm) {
        if (closed || pcm == null || pcm.length == 0) {
            return;
        }
        Consumer<byte[]> listener = this.callerListener;
        if (listener != null) {
            listener.accept(pcm);
        }
    }

    /** 连接就绪:注入 socket 并补发缓冲的下行音频。 */
    public synchronized void attach(AudioSocket socket) {
        this.socket = socket;
        if (!pending.isEmpty()) {
            log.debug("[CC-MEDIA] 补发缓冲下行音频 callId={} chunks={}", callId, pending.size());
            for (byte[] chunk : pending) {
                sendDownlink(chunk);
            }
            pending.clear();
        }
    }

    /** 连接断开:解绑 socket,后续下行直接丢弃(避免向死连接写)。 */
    public synchronized void detach() {
        this.socket = null;
    }

    public String callId() {
        return callId;
    }

    @Override
    public void onCallerAudio(Consumer<byte[]> listener) {
        this.callerListener = listener;
    }

    @Override
    public synchronized void writeTts(byte[] pcm8k) {
        if (closed || pcm8k == null || pcm8k.length == 0) {
            return;
        }
        if (socket == null) {
            // 连接未就绪:缓冲,连上后补发
            pending.add(pcm8k);
            return;
        }
        sendDownlink(pcm8k);
    }

    /** 编码 streamAudio JSON 下发;失败只记日志,不打断 TTS 合成线程。 */
    private void sendDownlink(byte[] pcm8k) {
        AudioSocket target = this.socket;
        if (target == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("audioDataType", "raw");
            data.put("sampleRate", 8000);
            data.put("audioData", Base64.getEncoder().encodeToString(pcm8k));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "streamAudio");
            payload.put("data", data);
            target.sendText(MAPPER.writeValueAsString(payload));
            // 估算播放截止时刻:按字节时长累加(近似,仅用于 barge-in)
            long now = System.currentTimeMillis();
            long prev = playingUntilMs.get();
            long base = Math.max(prev, now);
            playingUntilMs.set(base + pcm8k.length * 1000L / BYTES_PER_SECOND);
        } catch (Exception e) {
            log.warn("[CC-MEDIA] 下行音频发送失败 callId={}: {}", callId, e.getMessage());
        }
    }

    @Override
    public void interruptPlayback() {
        playingUntilMs.set(0);
        AudioSocket target = this.socket;
        if (target == null) {
            return;
        }
        try {
            target.sendText("{\"type\":\"clearAudio\"}");
        } catch (Exception e) {
            log.warn("[CC-MEDIA] 打断指令发送失败 callId={}: {}", callId, e.getMessage());
        }
    }

    @Override
    public boolean isBotPlaying() {
        return !closed && System.currentTimeMillis() < playingUntilMs.get();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending.clear();
        playingUntilMs.set(0);
        AudioSocket target = this.socket;
        this.socket = null;
        if (target != null) {
            try {
                target.close();
            } catch (Exception e) {
                log.debug("[CC-MEDIA] 关闭音频通道异常 callId={}: {}", callId, e.getMessage());
            }
        }
    }

    /**
     * 音频通道抽象:隔离 Spring WebSocketSession,便于单测不启容器。
     * 真实实现见 {@link FsMediaStreamProvider} 内部对 WebSocketSession 的适配。
     */
    public interface AudioSocket {
        void sendText(String text) throws Exception;

        void close() throws Exception;

        boolean isOpen();
    }
}
