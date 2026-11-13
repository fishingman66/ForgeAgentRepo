package com.forgeagent.callcenter.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活通道注册表(C4):callId → 当前在线的 {@link ChannelControl}。
 *
 * <p>用途:洞察管道是旁路能力,拿不到会话对象;负面情绪自动转接需要在
 * 「通话还在进行」时下发 uuid_transfer,于是一张只读快照表是必要的最小连接件。</p>
 *
 * <p>边界:只登记真实持有媒体面的会话(AICC 回调链路无媒体面,查不到即降级为
 * 只记录决策不执行转接);会话结束时必须 unregister,避免长驻引用。</p>
 */
@Slf4j
@Component
public class LiveChannelRegistry {

    private final Map<String, ChannelControl> channels = new ConcurrentHashMap<>();

    /** 登记一路活通道;同 callId 覆盖(重连场景以最新为准)。 */
    public void register(String callId, ChannelControl channel) {
        if (callId == null || callId.isBlank() || channel == null) {
            return;
        }
        channels.put(callId, channel);
    }

    /** 注销一路通道;只注销同一实例,避免误删重连后的新会话。 */
    public void unregister(String callId, ChannelControl channel) {
        if (callId == null || channel == null) {
            return;
        }
        channels.remove(callId, channel);
    }

    /** 查在线通道;不存在返回 null(调用方需按降级处理)。 */
    public ChannelControl find(String callId) {
        return callId == null ? null : channels.get(callId);
    }

    /** 当前在线通道数(可观测性用)。 */
    public int size() {
        return channels.size();
    }
}
