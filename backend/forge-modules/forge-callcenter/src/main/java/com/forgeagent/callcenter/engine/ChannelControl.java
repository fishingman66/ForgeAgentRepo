package com.forgeagent.callcenter.engine;

/**
 * 通道控制接口(C1 / 方案 docs/07 §三.4):对单路通话的命令面,语义对齐 FreeSWITCH
 * ESL uuid 命令(answer / uuid_transfer / uuid_broadcast / uuid_record / hangup)。
 *
 * <p>消费方是对话引擎(C2 级联链路 / C3 IVR 节点引擎);实现两态:
 * {@code FsChannelControl}(C1-real,ESL api/uuid 命令)与
 * {@code MockChannelControl}(C1,内存轨迹 + 可编程事件回调)。
 * 方法命名保持平台中立,FS 差异隔离在实现内。</p>
 */
public interface ChannelControl {

    /** 通道唯一标识,即通话 callId(FS 下为 Unique-ID)。 */
    String uuid();

    /** 应答(接通)。 */
    void answer();

    /**
     * 播放音频(下行,8kHz/16bit/mono PCM)。正在播放时再次调用由实现决定:
     * 排队追加(AFTER_TURN 语义)或打断替换——C2 对话引擎统一用 {@link #interruptPlayback()} 打断。
     */
    void playAudio(byte[] pcm8k);

    /** 打断当前播放:清空下行播放队列,barge-in 的本地侧动作。 */
    void interruptPlayback();

    /**
     * 转人工:进指定 ACD 队列(mod_callcenter queue)。
     * 转接结果(接通/放弃)由事件回流(call.seat_state / call.hangup),本方法不阻塞等待。
     */
    void transferToQueue(String queueName);

    /** 启动录音(录音文件路径由存储策略决定,实现内解析;合规链路不变)。 */
    void startRecording(String objectKey);

    /** 挂机。cause 口径对齐 FreeSWITCH Hangup-Cause(如 NORMAL_CLEARING)。 */
    void hangup(String cause);
}
