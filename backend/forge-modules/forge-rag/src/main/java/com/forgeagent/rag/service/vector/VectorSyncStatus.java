package com.forgeagent.rag.service.vector;

/**
 * 分块向量同步状态。
 *
 * <p>分块先落 MySQL 再写向量库,中间必然存在窗口:这里把窗口显式化,
 * 失败的分块由补偿扫描重试,而不是"写失败只打一行日志然后当没发生"。</p>
 *
 * @author ForgeAgent
 */
public final class VectorSyncStatus {

    /**
     * 已落库待写向量
     */
    public static final String PENDING = "PENDING";

    /**
     * 向量已写入
     */
    public static final String SYNCED = "SYNCED";

    /**
     * 写入失败,等待补偿
     */
    public static final String FAILED = "FAILED";

    /**
     * 无需向量(父块)或已被新代次取代的过期分块
     */
    public static final String SKIPPED = "SKIPPED";

    private VectorSyncStatus() {
    }
}
