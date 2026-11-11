package com.forgeagent.customerservice.transport;

import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsRealtimeTransport;

import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 通道实现(C0):复用 P0 的 RunEventBuffer,带单调序号 / 6h TTL / 断线重放。
 * 客服 run 键与控制台 run 隔离,不改全局 message.transport。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
@ConditionalOnProperty(prefix = "agent.customer-service", name = "transport",
    havingValue = "sse", matchIfMissing = true)
public class SseRealtimeTransport implements CsRealtimeTransport {

    private final RunEventBuffer runEventBuffer;
    private final CsModuleProperties properties;
    private final AtomicLong localSeq = new AtomicLong();

    @Override
    public String name() {
        return "sse";
    }

    @Override
    public void open(CsPeer peer) {
        runEventBuffer.publish(runKey(peer.conversationId()), "run_id",
            JsonUtils.toJsonString(java.util.Map.of("conversationId", peer.conversationId(),
                "channelCode", peer.channelCode())));
    }

    @Override
    public void push(CsEventEnvelope envelope) {
        CsEventEnvelope withSeq = envelope.sequence() > 0
            ? envelope : envelope.withSequence(localSeq.incrementAndGet());
        long n = runEventBuffer.publish(runKey(envelope.conversationId()), envelope.type(),
            JsonUtils.toJsonString(withSeq));
        if (n == 0) {
            log.warn("[CS] 事件缓冲不可用 convoId={}, Redis 降级为不可续传", envelope.conversationId());
        }
    }

    @Override
    public void close(Long conversationId, String reason) {
        runEventBuffer.terminal(runKey(conversationId), reason == null ? "done" : reason);
    }

    /** 与控制台 runId 命名空间隔离,避免事件串流。 */
    public static String runKey(Long conversationId) {
        return "cs:" + conversationId;
    }
}
