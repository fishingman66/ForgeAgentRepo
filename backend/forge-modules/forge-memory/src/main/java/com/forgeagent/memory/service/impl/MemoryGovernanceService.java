package com.forgeagent.memory.service.impl;

import com.forgeagent.memory.config.MemoryGovernanceProperties;
import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.mapper.AgentMemoryMapper;
import com.forgeagent.memory.mapper.MemoryGovernanceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆治理(P3 / M4):L4 消息归档 + L5 TTL 过期与配额淘汰,统一一个定时入口。
 *
 * <p>默认关闭({@code memory.governance.enabled=false}),定时空转;运维验证可用
 * `POST /agent/memory/governance/run` 手动触发一次。淘汰进归档而不是物理删:
 * agent_memory_archive 保留原 id 与内容(向量不归档),误淘汰可人工捞回。</p>
 *
 * <p>三步的顺序刻意是 归档 → TTL → 配额:前两步先给主表瘦身,配额统计看到的是
 * 瘦身后的真实条数,避免刚过期又被当作超配额再淘汰一遍。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryGovernanceService {

    public static final String REASON_RETENTION = "RETENTION";
    public static final String REASON_TTL = "TTL";
    public static final String REASON_QUOTA = "QUOTA";

    private final MemoryGovernanceMapper governanceMapper;
    private final AgentMemoryMapper memoryMapper;
    private final MemoryGovernanceProperties properties;

    /** 定时治理;开关关闭时空转(周期配 0 会让应用起不来,开关别用周期表达)。 */
    @Scheduled(fixedDelayString = "${memory.governance.scan-ms:600000}")
    public void scheduledRun() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("[MemoryGovernance] 治理扫描失败(下轮重试): {}", e.getMessage());
        }
    }

    /** 手动触发(运维/e2e 用);返回各步的处理量 */
    public Map<String, Long> runOnce() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("archivedMessages", runArchive());
        result.put("ttlArchived", runTtlSweep());
        result.put("quotaEvicted", runQuotaSweep());
        if (log.isInfoEnabled()) {
            log.info("[MemoryGovernance] 治理完成: 消息归档 {} 条 / TTL 归档 {} 条 / 配额淘汰 {} 条",
                result.get("archivedMessages"), result.get("ttlArchived"), result.get("quotaEvicted"));
        }
        return result;
    }

    /** L4:消息归档,分批提交直到搬完或达到单次批数上限 */
    long runArchive() {
        int retentionDays = properties.getArchiveRetentionDays();
        if (retentionDays <= 0) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < Math.max(1, properties.getArchiveMaxBatches()); i++) {
            // 截断到秒:MySQL DATETIME 不存纳秒,回读按 archive_time 对账时必须口径一致
            LocalDateTime stamp = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            int archived = governanceMapper.archiveMessages(retentionDays, properties.getArchiveBatch(), stamp);
            if (archived == 0) {
                break;
            }
            total += archived;
            governanceMapper.deleteArchivedMessages(stamp);
            if (archived < properties.getArchiveBatch()) {
                break;
            }
        }
        return total;
    }

    /** L5 TTL:显式 expire_at 过期分批归档;ttl-days>0 时的隐式过期同批兜住(注入侧最多滞后一个扫描周期) */
    long runTtlSweep() {
        long total = 0;
        int batch = Math.max(1, properties.getArchiveBatch());
        for (int i = 0; i < Math.max(1, properties.getArchiveMaxBatches()); i++) {
            LocalDateTime stamp = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            int archived = governanceMapper.archiveExpiredMemories(
                Math.max(0, properties.getTtlDays()), batch, stamp);
            if (archived == 0) {
                break;
            }
            total += archived;
            governanceMapper.deleteExpiredMemories(stamp);
            if (archived < batch) {
                break;
            }
        }
        return total;
    }

    /** L5 配额:超限的 (agent, 用户) 组按评分淘汰最低分差额,单组有淘汰上限 */
    long runQuotaSweep() {
        int defaultQuota = Math.max(0, properties.getDefaultQuota());
        List<Map<String, Object>> groups = governanceMapper.countOverQuotaGroups(defaultQuota);
        if (groups == null || groups.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (Map<String, Object> group : groups) {
            long cnt = ((Number) group.get("cnt")).longValue();
            int quota = group.get("quota") == null ? 0 : ((Number) group.get("quota")).intValue();
            if (quota <= 0 || cnt <= quota) {
                continue;
            }
            total += evictGroup(((Number) group.get("agentId")).longValue(),
                ((Number) group.get("userId")).longValue(), cnt - quota);
        }
        return total;
    }

    /** 抽取写入前的即时配额检查:先淘汰再插入,超限不会把最新记忆顶出去 */
    public int evictBeforeInsert(Long agentId, Long userId, int incoming) {
        if (agentId == null || userId == null) {
            return 0;
        }
        Long count = memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)
            .eq(AgentMemory::getUserId, userId));
        int quota = effectiveQuota(agentId);
        if (quota <= 0 || count == null || count + incoming <= quota) {
            return 0;
        }
        return evictGroup(agentId, userId, count + incoming - quota);
    }

    /** 淘汰一组里评分最低的差额条数;候选池按 id 倒序拉全量,评分与注入同一口径 */
    private int evictGroup(Long agentId, Long userId, long overCount) {
        if (overCount <= 0) {
            return 0;
        }
        int evict = (int) Math.min(overCount, Math.max(1, properties.getQuotaEvictBatch()));
        List<AgentMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)
            .eq(AgentMemory::getUserId, userId)
            .orderByDesc(AgentMemory::getId)
            .last("limit " + Math.max(1, properties.getQuotaEvictBatch() * 4)));
        if (rows.isEmpty()) {
            return 0;
        }
        List<AgentMemory> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Double.compare(
            AgentMemoryServiceImpl.scoreOf(a), AgentMemoryServiceImpl.scoreOf(b)));
        int done = 0;
        for (AgentMemory victim : sorted) {
            if (done >= evict) {
                break;
            }
            if (victim.getId() == null) {
                continue;
            }
            governanceMapper.archiveMemory(victim, REASON_QUOTA);
            memoryMapper.deleteById(victim.getId());
            done++;
        }
        return done;
    }

    private int effectiveQuota(Long agentId) {
        // agent_info.memory_quota 优先,未配置回落全局默认;两处都是 0 表示不限
        Integer quota = memoryMapper.selectAgentQuota(agentId);
        if (quota != null && quota != 0) {
            return quota;
        }
        return Math.max(0, properties.getDefaultQuota());
    }
}
