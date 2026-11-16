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

    // ---------------- 08方案 / B3:关键词通道(MySQL FULLTEXT ngram) ----------------

    /**
     * 是否启用关键词召回通道。
     *
     * <p>独立于语义向量开关:向量关闭时关键词路照样补上中文短事实召回。
     * FULLTEXT 索引未建时查询报错,自动降级为评分召回并按错误信息去重告警,不影响对话。</p>
     */
    private boolean keywordEnabled = true;

    /** 关键词召回条数 */
    private int keywordTopK = 10;

    /**
     * 融合分里关键词通道的权重(γ);α=semanticWeight×(1-γ),β=(1-semanticWeight)×(1-γ)。
     *
     * <p>0.4 的取值保证 FTS 相关度前 2 名(1.0/0.9)能压过评分池的归一满分
     * (β=0.32),否则关键词精确命中永远挤不进注入——语义通道靠 α=0.48 天然够强,
     * 关键词通道必须靠 γ 本身给足竞争力。γ=0 退化为双路融合。</p>
     */
    private double keywordWeight = 0.4;
}
