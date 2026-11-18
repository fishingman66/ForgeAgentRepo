package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.engine.ivr.IvrSessionDriver;
import com.forgeagent.callcenter.gateway.VoiceProvider;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IVR 会话协调器(P3 生产接入):接通后自动驱动 {@link IvrSessionDriver} 跑 IVR 流程。
 *
 * <p>装配链路:接通 → {@code mediaStreamProvider.open(callId)} 取媒体流 →
 * 会话线程池提交阻塞式 {@code drive(...)}(内部装配引擎与 DTMF 收号口)→
 * 挂机时置位 hangupFlag,流程在当前节点边界退出。</p>
 *
 * <p>边界:仅 provider=FS 且 {@code ivr-autodrive=true} 时驱动;流程加载失败/线程池拒绝
 * 只记日志,通话继续走事件链路(人工接听兜底),绝不让 IVR 故障打挂回调线程。</p>
 */
@Slf4j
@Service
@ConditionalOnCallcenterEnabled
public class FsIvrSessionCoordinator {

    private final ObjectProvider<MediaStreamProvider> mediaStreamProvider;
    private final IvrSessionDriver driver;
    private final CallcenterProperties properties;

    /** callId → 会话句柄(hangupFlag + future),挂机置位/泄漏排查用。 */
    private final Map<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final ExecutorService pool;

    private record SessionHandle(AtomicBoolean hangupFlag, Future<?> future) {
    }

    public FsIvrSessionCoordinator(ObjectProvider<MediaStreamProvider> mediaStreamProvider,
                                   IvrSessionDriver driver, CallcenterProperties properties) {
        this.mediaStreamProvider = mediaStreamProvider;
        this.driver = driver;
        this.properties = properties;
        int size = Math.max(1, properties.getIvrPoolSize() == null ? 4 : properties.getIvrPoolSize());
        this.pool = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "ivr-session");
            t.setDaemon(true);
            return t;
        });
    }

    /** 接通:打开媒体流并提交 IVR 会话;任何失败只降级记日志。 */
    public void onAnswered(String mediaProvider, CsCall call, ChannelControl channel) {
        if (!VoiceProvider.FS.code().equalsIgnoreCase(mediaProvider)
            || !Boolean.TRUE.equals(properties.getIvrAutodrive())) {
            return;
        }
        String callId = call.getCallId();
        if (callId == null || callId.isBlank() || sessions.containsKey(callId)) {
            return;
        }
        MediaStreamProvider provider = mediaStreamProvider.getIfAvailable();
        if (provider == null) {
            log.warn("[CC-IVR] 媒体流提供者未装配,跳过 IVR 驱动 callId={}", callId);
            return;
        }
        if (channel == null) {
            log.warn("[CC-IVR] 通道未登记,跳过 IVR 驱动 callId={}", callId);
            return;
        }
        try {
            MediaStream stream = provider.open(callId);
            AtomicBoolean hangupFlag = new AtomicBoolean(false);
            Future<?> future = pool.submit(() -> runSession(call, stream, channel, hangupFlag));
            sessions.put(callId, new SessionHandle(hangupFlag, future));
            log.info("[CC-IVR] IVR 会话已启动 callId={} route={} pool={}",
                callId, properties.getFreeswitch().getIvrRouteCode(), pool);
        } catch (RejectedExecutionException e) {
            log.error("[CC-IVR] 会话线程池已满,IVR 未驱动 callId={} poolSize={}",
                callId, properties.getIvrPoolSize());
        } catch (Exception e) {
            log.error("[CC-IVR] IVR 会话启动失败 callId={}: {}", callId, e.getMessage());
        }
    }

    /** 挂机:置位 hangupFlag,流程在节点边界退出;句柄由 runSession 收尾移除。 */
    public void onEnded(String callId) {
        SessionHandle handle = callId == null ? null : sessions.get(callId);
        if (handle != null) {
            handle.hangupFlag().set(true);
            log.info("[CC-IVR] 已通知会话挂机 callId={}", callId);
        }
    }

    private void runSession(CsCall call, MediaStream stream, ChannelControl channel, AtomicBoolean hangupFlag) {
        String callId = call.getCallId();
        try {
            driver.drive(call.getWorkspaceId(), properties.getFreeswitch().getIvrRouteCode(),
                callId, call.getCallerNumber(), call.getCalleeNumber(), stream, channel, hangupFlag);
        } catch (Exception e) {
            // 流程不存在/引擎缺失等:会话失败不影响事件链路,通道由 registrar 的挂机钩子统一注销
            log.error("[CC-IVR] 会话异常终止 callId={}: {}", callId, e.getMessage());
        } finally {
            sessions.remove(callId);
            try {
                stream.close();
            } catch (Exception e) {
                log.debug("[CC-IVR] 媒体流关闭异常 callId={}: {}", callId, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
