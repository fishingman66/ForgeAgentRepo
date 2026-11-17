package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体流 Hub(C1-real):承载 mod_audio_stream 的 WebSocket 接入。
 *
 * <p><b>方向</b>:FreeSWITCH 是 WebSocket 客户端,主动连本服务;端点为
 * {@code /cc/media/{callId}}。callId 即 FS 侧 Unique-ID,与业务 callId 一致,
 * 由 dialplan 执行 {@code uuid_audio_stream start ws://<host>:<port>/cc/media/<callId> ...} 时写入 URL。</p>
 *
 * <p>连接建立后把 {@link FsMediaStream.AudioSocket} 注入对应 media stream,
 * 上行二进制帧(PCM16LE)直送 {@link FsMediaStream#onCallerFrame}。</p>
 *
 * <p>与 {@link FsMediaStreamProvider#open} 的时序解耦:open 先到时 stream 处于缓冲态,
 * 连接到达后 attach 补发;连接先到时 Hub 暂存 socket,open 时立即绑定。</p>
 */
@Slf4j
@Component
@ConditionalOnCallcenterEnabled
@ConditionalOnProperty(prefix = "agent.callcenter", name = "media-stream-provider", havingValue = "fs")
public class MediaStreamHub extends AbstractWebSocketHandler {

    /** callId -> 已建流的 socket(open 之后才建立映射)。 */
    private final Map<String, FsMediaStream> streams = new ConcurrentHashMap<>();
    /** callId -> 先于 open 到达的连接(inbound 场景常见)。 */
    private final Map<String, WebSocketSession> earlySessions = new ConcurrentHashMap<>();

    /** Provider.open 时登记流实例,并把已提前到达的连接绑定上。 */
    public void register(String callId, FsMediaStream stream) {
        streams.put(callId, stream);
        WebSocketSession early = earlySessions.remove(callId);
        if (early != null) {
            bind(callId, stream, early);
        }
    }

    /** Provider 关闭流时解除映射;socket 已由 FsMediaStream.close 关闭。 */
    public void unregister(String callId, FsMediaStream stream) {
        streams.remove(callId, stream);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String callId = callIdOf(session);
        if (callId == null) {
            log.warn("[CC-MEDIA] 连接缺少 callId 路径参数,拒绝 uri={}", session.getUri());
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        FsMediaStream stream = streams.get(callId);
        if (stream == null) {
            // open 尚未发生:先暂存,等 open 时绑定
            earlySessions.put(callId, session);
            log.info("[CC-MEDIA] 连接先于 open 到达,暂存 callId={}", callId);
            return;
        }
        bind(callId, stream, session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String callId = callIdOf(session);
        FsMediaStream stream = callId == null ? null : streams.get(callId);
        if (stream == null) {
            return;
        }
        byte[] payload = new byte[message.getPayloadLength()];
        message.getPayload().get(payload);
        stream.onCallerFrame(payload);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // mod_audio_stream 的 connect/disconnect/json 事件文本,当前只记日志便于排障
        log.debug("[CC-MEDIA] 收到控制文本 callId={} body={}", callIdOf(session), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String callId = callIdOf(session);
        if (callId == null) {
            return;
        }
        earlySessions.remove(callId, session);
        FsMediaStream stream = streams.get(callId);
        if (stream != null) {
            stream.detach();
        }
        log.info("[CC-MEDIA] 连接关闭 callId={} status={}", callId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[CC-MEDIA] 传输异常 callId={}: {}", callIdOf(session), exception.getMessage());
    }

    /** 当前在线连接数(可观测性)。 */
    public int activeCount() {
        return streams.size();
    }

    // ---- 内部 ----

    private void bind(String callId, FsMediaStream stream, WebSocketSession session) {
        stream.attach(new SessionSocket(session));
        log.info("[CC-MEDIA] 媒体通道已绑定 callId={} uri={}", callId, session.getUri());
    }

    /** 从 uri 最后一段取 callId:/cc/media/{callId}。 */
    static String callIdOf(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getPath() == null) {
            return null;
        }
        String path = uri.getPath();
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        String callId = path.substring(idx + 1);
        return callId.isBlank() ? null : callId;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("[CC-MEDIA] 关闭连接异常: {}", e.getMessage());
        }
    }

    /** WebSocketSession -> AudioSocket 适配;下行发送在会话锁内完成,避免并发写同一 WS。 */
    private static final class SessionSocket implements FsMediaStream.AudioSocket {
        private final WebSocketSession session;

        private SessionSocket(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void sendText(String text) throws Exception {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }

        @Override
        public boolean isOpen() {
            return session.isOpen();
        }
    }
}
