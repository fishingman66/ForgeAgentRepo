package com.forgeagent.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * L3 长期语义记忆配置(P3 / M2')。
 *
 * <p>默认关闭:未显式开启时 buildMemoryBlock 完全走 M1.5 的纯评分召回,
 * 保证升级后行为不变、零额外开销。</p>
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "memory.vector")
public class MemoryVectorProperties {

    /** 是否启用语义向量召回 */
    private boolean enabled = false;

    /**
     * 后端:none(显式关闭) / mysql(向量列,本期唯一实现)。
     *
     * <p>pgvector 等其余取值是预留位,没有对应读写实现:配成这些值不会半启用,
     * 而是按关闭处理并告警一次,避免「配置切了库、数据仍在 MySQL」的静默错配。</p>
     */
    private String backend = "mysql";

    /** 向量化模型:local-hash(零外部依赖) 或 agent_model 中配置的 OpenAI 兼容嵌入模型名 */
    private String model = "local-hash";

    /** 语义召回条数 */
    private int topK = 20;

    /** 最低余弦相似度,低于此值不参与融合 */
    private double minScore = 0.2;

    /** 单次检索最多扫描的记忆条数(防大表把堆与响应时间拉爆) */
    private int scanLimit = 5000;

    /** 每轮对话前补偿向量化的最大条数 */
    private int pendingBatch = 20;

    /** 融合分里语义相似度的权重,其余给评分召回 */
    private double semanticWeight = 0.6;
}
