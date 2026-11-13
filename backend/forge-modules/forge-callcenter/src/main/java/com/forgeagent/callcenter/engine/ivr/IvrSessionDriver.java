package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.engine.MediaStream;
import com.forgeagent.callcenter.engine.MediaStreamProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IVR 会话驱动器(C3):一通电话跑一条流程的总入口——
 * 加载流程 → 装配上下文(callId/caller/turnEngine/callerChannel/channel/registry)
 * → 驱动同步循环;挂机由事件链路置位 hangupFlag,当前节点边界退出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvrSessionDriver {

    private final IvrFlowService flowService;
    private final NodeExecutorRegistry executorRegistry;
    private final CascadeEngineFactory engineFactory;
    private final MediaStreamProvider mediaStreamProvider;
    private final CallcenterProperties properties;
    private final LiveChannelRegistry liveChannels;

    /**
     * 驱动一通电话的呼入流程(阻塞调用,由通话线程执行;挂机即返回)。
     *
     * @param stream        媒体流(C1-real 为 mod_audio_fsc 会话,Mock 下为注入管道)
     * @param channel       通道命令
     * @param callerChannel DTMF 投递口(事件链路持有,Mock/测试直喂)
     * @param hangupFlag    挂机标志(事件链路置位)
     * @return 终止原因(completed/interrupted)
     */
    public String drive(Long workspaceId, String routeCode, String callId,
                        String caller, String callee,
                        MediaStream stream, ChannelControl channel,
                        CascadeEngineFactory.CallerChannel callerChannel,
                        AtomicBoolean hangupFlag) {
        Map<String, IvrNode> nodes = flowService.load(workspaceId, routeCode);
        CascadeEngineFactory.Session session = engineFactory.create(
            callId, stream, channel, properties.getAsrEngine(), properties.getTtsEngine());
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
            liveChannels.unregister(callId, channel);
            session.turnEngine().close();
        }
    }
}
