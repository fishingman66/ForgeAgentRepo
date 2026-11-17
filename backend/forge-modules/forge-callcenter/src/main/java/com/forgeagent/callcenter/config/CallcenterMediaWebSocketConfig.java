package com.forgeagent.callcenter.config;

import com.forgeagent.callcenter.engine.MediaStreamHub;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 媒体面 WebSocket 注册(C1-real):把 {@link MediaStreamHub} 挂到 {@code /cc/media/**}。
 *
 * <p>路径末尾即 callId(dialplan 中 {@code uuid_audio_stream start ws://host:port/cc/media/<uuid>}),
 * 由 Hub 从 URI 解析。仅内网可达:FS 与后端同机,端点不对公网开放。
 * 与文本域的消息推送 WebSocket({@code forge-common-push})路径不冲突。</p>
 *
 * <p>不设握手鉴权:mod_audio_stream 无法携带自定义鉴权头;安全边界靠
 * 「ESL/媒体端口只绑内网 + 防火墙」,与 ESL 8021 同一策略。</p>
 */
@Configuration
@EnableWebSocket
@ConditionalOnCallcenterEnabled
@ConditionalOnProperty(prefix = "agent.callcenter", name = "media-stream-provider", havingValue = "fs")
public class CallcenterMediaWebSocketConfig implements WebSocketConfigurer {

    private final MediaStreamHub hub;

    public CallcenterMediaWebSocketConfig(MediaStreamHub hub) {
        this.hub = hub;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/cc/media/**").setAllowedOriginPatterns("*");
    }
}
