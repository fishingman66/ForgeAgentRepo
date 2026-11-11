package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库检索评测运行记录 agent_kb_eval_run
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_eval_run")
public class AgentKbEvalRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属知识库ID
     */
    private Long knowledgeId;

    /**
     * 评测时的召回条数
     */
    private Integer topK;

    /**
     * 用例总数
     */
    private Integer totalCases;

    /**
     * 命中用例数
     */
    private Integer hitCases;

    /**
     * 平均倒数排名(MRR,0~1)
     */
    private Double mrr;

    /**
     * HitRate@K(命中用例/有效用例)
     */
    private Double hitRate;

    /**
     * Recall@K(平均证据覆盖度)
     */
    private Double recallAtK;

    /**
     * Precision@K(相关条数/K)
     */
    private Double precisionAtK;

    /**
     * NDCG@K(二值相关,ideal=min(max(标注数,观测数),K))
     */
    private Double ndcgAtK;

    /**
     * 五指标+分组+Wilson95%置信区间明细 JSON
     */
    private String metricsJson;

    /**
     * 评测时配置快照 JSON(嵌入模型/分块参数/阈值)
     */
    private String configSnapshot;

    /**
     * 语料指纹 sha256(排序后的 (documentId, activeGeneration) | embeddingModel:dim | chunkFingerprint)
     */
    private String corpusFingerprint;

    /**
     * 数据集修订号
     */
    private String datasetRevision;

    /**
     * 有效用例数(ACTIVE)
     */
    private Integer effectiveCases;

    /**
     * 证据过期用例数(EVIDENCE_STALE,不入指标)
     */
    private Integer staleCases;

    /**
     * 检索降级用例数
     */
    private Integer degradedCases;

    /**
     * 门禁判决:PASSED / BLOCKED / LOG_ONLY
     */
    private String gateState;

    /**
     * 门禁原因:METRICS_REGRESSED / WITHIN_TOLERANCE / BASELINE_RECORDED /
     * NOT_COMPARABLE / STALE_RATIO_EXCEEDED / DEGRADED_CASES / INSUFFICIENT_CASES
     */
    private String gateReason;

    /**
     * 逐用例判定明细 JSON(交集重算与门禁对比用)
     */
    private String caseResultsJson;

    /**
     * 命中用例的平均相关度分
     */
    private Double avgScore;

    /**
     * 评测耗时(毫秒)
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private Date createTime;
}
