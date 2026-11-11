package com.forgeagent.agent.service.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.redis.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Run 级 SSE 事件缓冲(P0 / A1)。
 *
 * <p>以 Redis 有序集合按 run 内单调递增序号保存事件,客户端带 {@code Last-Event-ID}
 * 重连后从断点重放,终态标记用于判定何时收口。缓冲上限 4000 条、TTL 6 小时,
 * Redis 不可用时降级为「原始 SSE 仍可用、只是不支持续传」,绝不阻断对话。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class RunEventBuffer {

    private static final String PREFIX = "forge:run:";
    private static final int MAX_EVENTS = 4000;
    private static final Duration TTL = Duration.ofHours(6);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条可重放的 SSE 事件 */
    public record RunEvent(long sequence, String name, String data) {
    }

    /** 持久化并返回 run 内单调递增序号;失败返回 0 表示本次不参与续传 */
    public long publish(String runId, String event, String payload) {
        if (runId == null) {
            return 0;
        }
        try {
            RAtomicLong seq = RedisUtils.getClient().getAtomicLong(PREFIX + runId + ":seq");
            long n = seq.incrementAndGet();
            RScoredSortedSet<String> events = RedisUtils.getClient()
                .getScoredSortedSet(PREFIX + runId + ":events");
            events.add(n, JsonUtils.toJsonString(
                Map.of("sequence", n, "name", event, "data", payload == null ? "" : payload)));
            // TTL 只在首帧设置;INCR/ZADD 不会清除已有过期时间,避免每个 delta 多两次 Redis 往返
            if (n == 1) {
                events.expire(TTL);
                seq.expire(TTL);
            }
            if (n > MAX_EVENTS) {
                events.removeRangeByScore(0, false, (double) (n - MAX_EVENTS), true);
            }
            return n;
        } catch (Exception e) {
            log.warn("[RunEventBuffer] 事件缓冲写入失败 runId={}: {}", runId, e.getMessage());
            return 0;
        }
    }

    /** 标记运行终态(done / error / cancelled) */
    public void terminal(String runId, String status) {
        if (runId == null) {
            return;
        }
        try {
            RBucket<String> bucket = RedisUtils.getClient().getBucket(PREFIX + runId + ":terminal");
            bucket.set(status);
            bucket.expire(TTL);
        } catch (Exception e) {
            log.warn("[RunEventBuffer] 终态标记失败 runId={}: {}", runId, e.getMessage());
        }
    }

    /** 终态状态,未结束返回 null */
    public String terminalOf(String runId) {
        try {
            return RedisUtils.getClient().<String>getBucket(PREFIX + runId + ":terminal").get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取指定序号之后的全部事件,按序号升序 */
    public List<RunEvent> replay(String runId, long afterSequence) {
        if (runId == null) {
            return List.of();
        }
        try {
            RScoredSortedSet<String> events = RedisUtils.getClient()
                .getScoredSortedSet(PREFIX + runId + ":events");
            Collection<String> raw = events.valueRange(
                (double) afterSequence + 1, true, Double.MAX_VALUE, true);
            List<RunEvent> result = new ArrayList<>(raw.size());
            for (String item : raw) {
                Map<String, Object> map = MAPPER.readValue(item, new TypeReference<>() {
                });
                long sequence = ((Number) map.getOrDefault("sequence", 0L)).longValue();
                result.add(new RunEvent(sequence,
                    String.valueOf(map.getOrDefault("name", "message")),
                    String.valueOf(map.getOrDefault("data", ""))));
            }
            return result;
        } catch (Exception e) {
            log.warn("[RunEventBuffer] 事件重放失败 runId={}: {}", runId, e.getMessage());
            return List.of();
        }
    }
}
