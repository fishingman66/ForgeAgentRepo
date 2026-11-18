package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.engine.MediaStream;
import com.forgeagent.callcenter.engine.MediaStreamProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IVR 会话驱动器(C3):一通电话跑一条流程的总入口——
 * 加载流程 → 装配上下文(callId/caller/turnEngine/callerChannel/channel/registry)
 * → 驱动同步循环;挂机由事件链路置位 hangupFlag,当前节点边界退出。
 *
 * <p>P3 生产接入:callerChannel 由本类内部创建(turnEngine 在 drive 内装配,外部拿不到),
 * 并登记进活跃表——事件链路经 {@link #offerDtmf} 把 DTMF 按键投进收号队列,
 * 会话结束自动移除。{@code CascadeEngineFactory.CallerChannel} 的手动构造只保留给测试直喂。</p>
 */
@Slf4j
@Service
public class IvrSessionDriver {

    private final IvrFlowService flowService;
    private final NodeExecutorRegistry executorRegistry;
    private final CascadeEngineFactory engineFactory;
    private final MediaStreamProvider mediaStreamProvider;
    private final CallcenterProperties properties;
    private final LiveChannelRegistry liveChannels;

    /** 活跃会话的 DTMF 收号口:callId → callerChannel,drive 结束移除。 */
    private final Map<String, CascadeEngineFactory.CallerChannel> activeCallers = new ConcurrentHashMap<>();

    public IvrSessionDriver(IvrFlowService flowService, NodeExecutorRegistry executorRegistry,
                            CascadeEngineFactory engineFactory, MediaStreamProvider mediaStreamProvider,
                            CallcenterProperties properties, LiveChannelRegistry liveChannels) {
        this.flowService = flowService;
        this.executorRegistry = executorRegistry;
        this.engineFactory = engineFactory;
        this.mediaStreamProvider = mediaStreamProvider;
        this.properties = properties;
        this.liveChannels = liveChannels;
    }

    /**
     * 驱动一通电话的呼入流程(阻塞调用,由通话线程执行;挂机即返回)。
     *
     * @param stream        媒体流(C1-real 为 mod_audio_stream 会话,Mock 下为注入管道)
     * @param channel       通道命令
     * @param hangupFlag    挂机标志(事件链路置位)
     * @return 终止原因(completed/interrupted)
     */
    public String drive(Long workspaceId, String routeCode, String callId,
                        String caller, String callee,
                        MediaStream stream, ChannelControl channel,
                        AtomicBoolean hangupFlag) {
        Map<String, IvrNode> nodes = flowService.load(workspaceId, routeCode);
        CascadeEngineFactory.Session session = engineFactory.create(
            callId, stream, channel, properties.getAsrEngine(), properties.getTtsEngine());
        // P3:callerChannel 内部装配并登记,DTMF 经 offerDtmf 投递
        CascadeEngineFactory.CallerChannel callerChannel = new CascadeEngineFactory.CallerChannel(session.turnEngine());
        activeCallers.put(callId, callerChannel);
        // C4:登记活通道,供洞察管道的负面自动转接下发 uuid_transfer
        liveChannels.register(callId, channel);

        Map<String, Object> vars = new HashMap<>();
        vars.put("callId", callId);
        vars.put("caller", caller);
        vars.put("callee", callee);
        vars.put("workspaceId", workspaceId);
        vars.put("turnEngine", session.turnEngine());
        vars.put("callerChannel", callerChannel);
        vars.put("channel", channel);
        vars.put("executorRegistry", executorRegistry);

        NodeContext ctx = new NodeContext(vars);
        String startId = nodes.values().iterator().next().getId();
        try {
            String reason = new IvrFlowRunner().run(nodes, startId, ctx, hangupFlag);
            log.info("[CC][IVR] 流程结束 callId={} route={} reason={}", callId, routeCode, reason);
            return reason;
        } finally {
            activeCallers.remove(callId);
            liveChannels.unregister(callId, channel);
            session.turnEngine().close();
        }
    }

    /**
     * 投递 DTMF 按键(事件链路调用);会话不在跑或已结束时返回 false,调用方按普通事件落库即可。
     */
    public boolean offerDtmf(String callId, String digit) {
        CascadeEngineFactory.CallerChannel channel = callId == null ? null : activeCallers.get(callId);
        if (channel == null) {
            return false;
        }
        channel.offerDtmf(digit);
        return true;
    }

    /** 当前活跃 IVR 会话数(可观测性)。 */
    public int activeCount() {
        return activeCallers.size();
    }
}
