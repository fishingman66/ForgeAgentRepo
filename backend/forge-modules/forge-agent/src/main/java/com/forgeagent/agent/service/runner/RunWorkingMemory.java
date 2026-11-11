package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.common.redis.utils.RedisUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * L1 工作记忆(P3 / M1):run 作用域的工具原始输出暂存。
 *
 * <p>工具结果超过阈值时,原文存入 Redis(key 前缀 {@code agent:run:wm:{runId}:}),
 * 上下文里只留「指纹 + 开头片段 + 取回方式」;模型可经 {@code recall_working_memory}
 * 按指纹分段取回。条数与字符数都有上限,条目 TTL 到期自动清理,run 结束时主动清除。</p>
 *
 * <p>Redis 不可用或任一步失败一律返回 null,调用方保留原始工具结果,不阻断对话。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunWorkingMemory {

    private static final String PREFIX = "agent:run:wm:";
    /** 指纹白名单,避免模型借本工具读取任意 Redis key */
    private static final Pattern FP_PATTERN = Pattern.compile("wm-\\d{1,4}-[0-9a-f]{8}");

    private final AgentChatProperties chatProperties;

    /**
     * 暂存一条工具原始输出。
     *
     * @return 指纹;null 表示未压缩(应保留原结果)
     */
    public String store(String runId, String toolName, String text) {
        if (!enabled() || runId == null || text == null || text.isEmpty()) {
            return null;
        }
        int maxChars = intValue(chatProperties.getWorkingMemoryMaxChars(), 200000);
        if (text.length() > maxChars) {
            return null;
        }
        int ttl = intValue(chatProperties.getWorkingMemoryTtlSeconds(), 3600);
        int maxEntries = intValue(chatProperties.getWorkingMemoryMaxEntries(), 20);
        try {
            RAtomicLong seq = RedisUtils.getClient().getAtomicLong(PREFIX + runId + ":seq");
            long n = seq.incrementAndGet();
            if (n == 1) {
                seq.expire(Duration.ofSeconds(ttl));
            }
            if (n > maxEntries) {
                log.warn("[WorkingMemory] run {} 压缩条数已达上限 {},本次保留原结果", runId, maxEntries);
                return null;
            }
            String fingerprint = "wm-" + n + "-"
                + String.format(Locale.ROOT, "%08x", text.hashCode());
            RedisUtils.setCacheObject(PREFIX + runId + ":" + fingerprint, text,
                Duration.ofSeconds(ttl));
            return fingerprint;
        } catch (Exception e) {
            log.warn("[WorkingMemory] 暂存失败 run={} tool={}: {}", runId, toolName, e.getMessage());
            return null;
        }
    }

    /** 按指纹分段取回原文;指纹非法/条目过期给出可操作的说明文本 */
    public String recall(String runId, String fingerprint, int offset, int limit) {
        if (runId == null || fingerprint == null || !FP_PATTERN.matcher(fingerprint).matches()) {
            return "[工作记忆] 指纹不合法,请原样使用工具结果中给出的指纹。";
        }
        int maxRecall = intValue(chatProperties.getWorkingMemoryRecallLimitChars(), 4000);
        try {
            String text = RedisUtils.getCacheObject(PREFIX + runId + ":" + fingerprint);
            if (text == null) {
                return "[工作记忆] 该指纹对应的原文已过期或不存在,请重新调用原工具获取。";
            }
            int start = Math.min(Math.max(0, offset), text.length());
            int size = Math.min(Math.max(1, limit), maxRecall);
            int end = Math.min(text.length(), start + size);
            String tail = end < text.length()
                ? "\n…[还有 " + (text.length() - end) + " 字符,可增大 offset 继续读取]" : "";
            return "[工作记忆原文 " + fingerprint + ",区间 " + start + "-" + end
                + "/" + text.length() + "]\n" + text.substring(start, end) + tail;
        } catch (Exception e) {
            log.warn("[WorkingMemory] 取回失败 run={} fp={}: {}", runId, fingerprint, e.getMessage());
            return "[工作记忆] 原文读取失败,请重新调用原工具获取。";
        }
    }

    /** run 结束时清除该轮全部工作记忆键(仅匹配本前缀) */
    public void clear(String runId) {
        if (runId == null) {
            return;
        }
        try {
            RedisUtils.getClient().getKeys().deleteByPattern(PREFIX + runId + ":*");
        } catch (Exception e) {
            log.warn("[WorkingMemory] 清理失败 run={}: {}", runId, e.getMessage());
        }
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(chatProperties.getWorkingMemoryEnabled());
    }

    private static int intValue(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
