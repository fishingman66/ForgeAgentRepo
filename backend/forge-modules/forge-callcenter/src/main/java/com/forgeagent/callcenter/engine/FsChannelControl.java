package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import link.thingscloud.freeswitch.esl.InboundClient;
import lombok.extern.slf4j.Slf4j;

/**
 * FreeSWITCH 通道控制(C1-real):用 ESL {@code uuid_*} 命令驱动单路通话。
 *
 * <p>命令映射:
 * <ul>
 *   <li>{@code answer} → {@code uuid_answer &lt;uuid&gt;}</li>
 *   <li>{@code transferToQueue} → {@code uuid_transfer &lt;uuid&gt; &lt;号码&gt; XML &lt;context&gt;}</li>
 *   <li>{@code startRecording} → {@code uuid_record &lt;uuid&gt; start &lt;path&gt;}</li>
 *   <li>{@code hangup} → {@code uuid_kill &lt;uuid&gt; &lt;cause&gt;}</li>
 *   <li>{@code interruptPlayback} → {@code uuid_break &lt;uuid&gt; all}</li>
 * </ul>
 *
 * <p><b>P2 边界</b>:{@link #playAudio} 需要媒体面下行通道(mod_audio_stream WebSocket,P3 交付),
 * 当前无管道,只记降级日志、不谎称已播放——与项目「不静默假装成功」纪律一致。
 * 转人工不依赖媒体面,是 P2 的核心能力。</p>
 *
 * <p>command 口径:统一走 {@code sendAsyncApiCommand},避免 originate/transfer 这类
 * 可能阻塞的命令卡住事件分发线程。</p>
 */
@Slf4j
public class FsChannelControl implements ChannelControl {

    private final InboundClient client;
    private final String eslAddress;
    private final String uuid;
    private final CallcenterProperties.FreeswitchProperties config;

    public FsChannelControl(InboundClient client, String eslAddress, String uuid,
                            CallcenterProperties.FreeswitchProperties config) {
        this.client = client;
        this.eslAddress = eslAddress;
        this.uuid = uuid;
        this.config = config;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    @Override
    public void answer() {
        execute("uuid_answer", uuid);
    }

    @Override
    public void playAudio(byte[] pcm8k) {
        // P3 媒体面(mod_audio_stream 下行)就绪后改为写入 WebSocket 播放队列
        log.debug("[CC-ESL] playAudio 待媒体面(P3),忽略 {} 字节 uuid={}",
            pcm8k == null ? 0 : pcm8k.length, uuid);
    }

    @Override
    public void interruptPlayback() {
        execute("uuid_break", uuid + " all");
    }

    /**
     * 转人工:uuid_transfer 到 dialplan 号码,该号码的 extension 执行 callcenter 应用进场。
     * 队列名到号码的映射由 {@code queue-extensions} 配置;未配置则把队列名当号码。
     */
    @Override
    public void transferToQueue(String queueName) {
        String target = resolveQueueExtension(queueName);
        String context = config.getDialplanContext() == null || config.getDialplanContext().isBlank()
            ? "default" : config.getDialplanContext();
        execute("uuid_transfer", uuid + " " + target + " XML " + context);
        log.info("[CC-ESL] 已下发转人工 uuid={} queue={} target={} context={}",
            uuid, queueName, target, context);
    }

    @Override
    public void startRecording(String objectKey) {
        // 录音落盘路径由 recording-dir 决定;objectKey 只作为文件名片段
        String dir = config.getRecordingDir();
        String safeName = objectKey == null || objectKey.isBlank() ? uuid : objectKey;
        if (dir == null || dir.isBlank()) {
            log.warn("[CC-ESL] recording-dir 未配置,跳过 uuid_record uuid={}", uuid);
            return;
        }
        String path = dir.endsWith("/") ? dir + safeName : dir + "/" + safeName;
        if (!path.endsWith(".wav")) {
            path = path + ".wav";
        }
        execute("uuid_record", uuid + " start " + path);
    }

    @Override
    public void hangup(String cause) {
        String resolved = cause == null || cause.isBlank() ? "NORMAL_CLEARING" : cause;
        execute("uuid_kill", uuid + " " + resolved);
    }

    /** 队列名 -> dialplan 号码;queue-extensions 形如 after_sales:5000,after_sales:5001。 */
    String resolveQueueExtension(String queueName) {
        if (queueName == null || queueName.isBlank()) {
            return "5000";
        }
        String raw = config.getQueueExtensions();
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(queueName.trim())) {
                    return kv[1].trim();
                }
            }
        }
        return queueName.trim();
    }

    /** 命令下发:异常只记日志不外抛,单条命令失败不能打挂事件/引擎线程。 */
    private void execute(String command, String args) {
        try {
            String reply = client.sendAsyncApiCommand(eslAddress, command, args);
            if (reply != null && reply.contains("-ERR")) {
                log.warn("[CC-ESL] 命令被拒 cmd={} uuid={} reply={}", command, uuid, reply.trim());
            } else {
                log.debug("[CC-ESL] 命令已下发 cmd={} uuid={}", command, uuid);
            }
        } catch (Exception e) {
            log.error("[CC-ESL] 命令下发失败 cmd={} uuid={}: {}", command, uuid, e.getMessage());
        }
    }
}
