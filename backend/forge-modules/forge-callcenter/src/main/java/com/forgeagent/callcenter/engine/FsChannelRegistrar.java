package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.gateway.VoiceProvider;
import link.thingscloud.freeswitch.esl.InboundClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 真实会话入口(C1-real):把 ESL 通话的 {@link ChannelControl} 登记进 {@link LiveChannelRegistry},
 * 打通「业务决策 → 真实下发 uuid_* 命令」的最后一段。
 *
 * <p>为什么需要它:洞察管道与直接应答分流是旁路能力,拿不到会话对象,
 * 只通过 LiveChannelRegistry 查在线通道;此前没有任何生产代码向注册表写入,
 * 导致转人工只能留痕 {@code PENDING_NO_CHANNEL}。本类在接通过程中登记真实通道,
 * 挂机时注销,使负面自动转接 / 规则转人工真实生效。</p>
 *
 * <p>边界:仅对 {@code media_provider=FS} 且 ESL 客户端已就位时登记;
 * Mock/本地开发无真实 ESL 时静默跳过,不影响既有链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class FsChannelRegistrar {

    /** ObjectProvider:本地 dev / Mock 环境无真实 ESL bean 时不阻断,静默跳过。 */
    private final ObjectProvider<InboundClient> inboundClientProvider;
    private final LiveChannelRegistry liveChannels;
    private final CallcenterProperties properties;

    /**
     * 通话接通:为 FS 通话创建真实通道并登记。
     * 同一 callId 重复登记以最新为准(注册表内部覆盖)。
     */
    public void onAnswered(String mediaProvider, String callId) {
        if (!isRealFs(mediaProvider) || callId == null || callId.isBlank()) {
            return;
        }
        if (liveChannels.find(callId) != null) {
            // 已登记(重复 answer 事件):保留原通道,避免覆盖后旧实例泄漏
            return;
        }
        InboundClient client = inboundClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("[CC-ESL] ESL 客户端未装配,跳过通道登记 callId={}", callId);
            return;
        }
        CallcenterProperties.FreeswitchProperties fs = properties.getFreeswitch();
        String address = fs.getEslAddress() == null || fs.getEslAddress().isBlank()
            ? "127.0.0.1:8021" : fs.getEslAddress();
        FsChannelControl channel = new FsChannelControl(client, address, callId, fs);
        liveChannels.register(callId, channel);
        log.info("[CC-ESL] 已登记真实通道 callId={} addr={} online={}", callId, address, liveChannels.size());
        // P3:接通即启动媒体流,让 mod_audio_stream 连回本服务的 WebSocket 端点
        startMediaStream(client, address, callId, fs);
    }

    /**
     * 下发 {@code uuid_audio_stream start <ws-url> <mix> <rate>}:让 FS 把通话音频
     * 以 PCM16LE 二进制帧推到本服务的 {@code /cc/media/<callId>}。
     *
     * <p>mix=mono 只取 caller 音频(ASR 只需要客户说话);采样率取配置,
     * 8k 与 MediaStream/AsrEngine 接口口径一致,避免二次重采样。</p>
     */
    private void startMediaStream(InboundClient client, String address, String callId,
                                  CallcenterProperties.FreeswitchProperties fs) {
        String base = fs.getMediaWsBase();
        if (base == null || base.isBlank()) {
            log.warn("[CC-MEDIA] media-ws-base 未配置,跳过媒体流启动 callId={}", callId);
            return;
        }
        String wsUrl = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
            + "/cc/media/" + callId;
        String rate = fs.getMediaSampleRate() == null || fs.getMediaSampleRate().isBlank()
            ? "8k" : fs.getMediaSampleRate();
        try {
            String reply = client.sendAsyncApiCommand(address, "uuid_audio_stream",
                callId + " start " + wsUrl + " mono " + rate);
            if (reply != null && reply.contains("-ERR")) {
                log.warn("[CC-MEDIA] 媒体流启动被拒 callId={} reply={}", callId, reply.trim());
            } else {
                log.info("[CC-MEDIA] 已请求启动媒体流 callId={} url={} rate={}", callId, wsUrl, rate);
            }
        } catch (Exception e) {
            log.error("[CC-MEDIA] 媒体流启动失败 callId={}: {}", callId, e.getMessage());
        }
    }

    /** 通话结束:注销通道,避免长驻引用。 */
    public void onEnded(String callId) {
        if (callId == null || callId.isBlank()) {
            return;
        }
        ChannelControl channel = liveChannels.find(callId);
        if (channel != null) {
            liveChannels.unregister(callId, channel);
            log.info("[CC-ESL] 已注销通道 callId={} online={}", callId, liveChannels.size());
        }
    }

    private boolean isRealFs(String mediaProvider) {
        return VoiceProvider.FS.code().equalsIgnoreCase(mediaProvider);
    }
}
