package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FreeSWITCH 媒体流提供者(C1-real):{@link MediaStreamProvider} 的真实实现。
 *
 * <p>与 Mock 的区别:不自己造管道,而是把 mod_audio_stream 已建立的 WebSocket 会话
 * 包装成 {@link FsMediaStream}——打开即登记到 {@link MediaStreamHub},
 * 连接到达时由 Hub 绑定上;关闭时解除登记。</p>
 *
 * <p>边界:若 FS 侧尚未执行 {@code uuid_audio_stream start},open 返回的流处于
 * 「无连接缓冲态」,下行音频暂存、上行无帧;连接到达后自动补发。这让
 * 「先 open 后连接」与「先连接后 open」两种时序都能工作。</p>
 *
 * <p>与 {@link MockMediaStreamProvider} 互斥装配:{@code agent.callcenter.media-stream-provider=fs} 时启用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
@ConditionalOnProperty(prefix = "agent.callcenter", name = "media-stream-provider", havingValue = "fs")
public class FsMediaStreamProvider implements MediaStreamProvider {

    private final MediaStreamHub hub;

    @Override
    public MediaStream open(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("媒体流 uuid 不能为空");
        }
        FsMediaStream stream = new FsMediaStream(uuid);
        hub.register(uuid, stream);
        log.info("[CC-MEDIA] 打开真实媒体流 callId={}", uuid);
        return stream;
    }
}
