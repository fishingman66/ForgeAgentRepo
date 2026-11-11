package com.forgeagent.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆治理配置(P3 / M4:L4 归档 + L5 TTL/配额)。
 *
 * <p>全部默认关闭:治理是后台维护动作,开启前主链路行为与 M3 完全一致。
 * scan-ms 必须 >0 —— @Scheduled 的 fixedDelay 配 0 会抛 IllegalArgumentException
 * 直接导致应用起不来,开关请用 enabled,不要把周期配成 0。</p>
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "memory.governance")
public class MemoryGovernanceProperties {

    /** 治理总开关:关闭时定时任务空转,手动触发端点也不执行。 */
    private boolean enabled = false;

    /** 治理扫描周期(毫秒),必须 >0。 */
    private long scanMs = 600000L;

    /**
     * 消息归档(L4):会话最后消息时间超过 N 天且未置顶的消息转移到归档表;0=关闭归档。
     */
    private int archiveRetentionDays = 180;

    /** 单批归档的消息条数(分批提交避免长事务)。 */
    private int archiveBatch = 500;

    /** 单次治理最多跑多少批归档。 */
    private int archiveMaxBatches = 20;

    /**
     * 记忆 TTL(天):expire_at 为空时按「创建时间 + N 天」判定过期;0=只看 expire_at 列。
     */
    private int ttlDays = 0;

    /** 全局默认配额:agent_info.memory_quota 未配置时生效;0=不限。 */
    private int defaultQuota = 0;

    /** 单次治理每组最多淘汰的记忆条数(防大用户一次性清空)。 */
    private int quotaEvictBatch = 50;
}
