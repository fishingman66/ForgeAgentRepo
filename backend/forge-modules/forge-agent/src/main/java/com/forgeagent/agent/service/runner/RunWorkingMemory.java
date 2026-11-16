package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.common.redis.utils.RedisUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
            // 08方案 / C4:同时记下来源工具名,沉淀进长期记忆时标注出处
            RedisUtils.setCacheObject(PREFIX + runId + ":tool:" + fingerprint,
                toolName == null ? "unknown" : toolName, Duration.ofSeconds(ttl));
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
            recordHit(runId, fingerprint);
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

    /**
     * 记录一次成功取回(08方案 / C4 沉淀依据)。
     * 模型回访过的输出才有沉淀价值;计数键走独立前缀,clear 时一并删除。
     */
    private void recordHit(String runId, String fingerprint) {
        int ttl = intValue(chatProperties.getWorkingMemoryTtlSeconds(), 3600);
        try {
            RAtomicLong hits = RedisUtils.getClient()
                .getAtomicLong(PREFIX + runId + ":hit:" + fingerprint);
            if (hits.incrementAndGet() == 1) {
                hits.expire(Duration.ofSeconds(ttl));
            }
        } catch (Exception e) {
            log.warn("[WorkingMemory] 取回计数失败 run={} fp={}: {}", runId, fingerprint, e.getMessage());
        }
    }

    /**
     * 沉淀条目:指纹 + 来源工具名 + 原文
     */
    public record HotEntry(String fingerprint, String toolName, String text) {
    }

    /**
     * 收集取回次数达到阈值的热条目(08方案 / C4)。
     * 遍历本 run 的取回计数键,命中阈值才读原文与来源工具名;
     * 任一步失败只记日志,沉淀是尽力而为的旁路行为。
     */
    public List<HotEntry> collectHotEntries(String runId, int minRecalls) {
        if (runId == null || minRecalls <= 0) {
            return List.of();
        }
        List<HotEntry> hot = new ArrayList<>();
        try {
            Iterable<String> keys = RedisUtils.getClient().getKeys()
                .getKeysByPattern(PREFIX + runId + ":hit:*");
            for (String key : keys) {
                String fingerprint = key.substring((PREFIX + runId + ":hit:").length());
                RAtomicLong counter = RedisUtils.getClient().getAtomicLong(key);
                long recalls = counter.get();
                if (recalls < minRecalls) {
                    continue;
                }
                String text = RedisUtils.getCacheObject(PREFIX + runId + ":" + fingerprint);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                String toolName = RedisUtils.getCacheObject(PREFIX + runId + ":tool:" + fingerprint);
                hot.add(new HotEntry(fingerprint, toolName == null ? "unknown" : toolName, text));
            }
        } catch (Exception e) {
            log.warn("[WorkingMemory] 热条目收集失败 run={}: {}", runId, e.getMessage());
            return List.of();
        }
        hot.sort(java.util.Comparator.comparing(HotEntry::fingerprint));
        return hot;
    }

    /**
     * 构建沉淀进长期记忆的内容(08方案 / C4;静态纯函数,便于单测):
     * 标注来源工具与指纹,原文超长只取开头片段,保证记忆块预算不被单条撑爆。
     */
    public static String buildPersistContent(String toolName, String text, int maxChars) {
        String safeTool = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        String safeText = text == null ? "" : text.strip();
        if (safeText.isEmpty()) {
            return "";
        }
        String prefix = "[" + safeTool + " 输出摘录] ";
        String hint = "…(原文已截断,完整内容见原始工具调用)";
        int budget = Math.max(1, maxChars - prefix.length());
        if (safeText.length() <= budget) {
            return prefix + safeText;
        }
        // 截断提示本身也占预算,保证 prefix + body 恒 <= maxChars
        int keep = Math.max(1, budget - hint.length());
        return prefix + safeText.substring(0, keep) + hint;
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
