package com.forgeagent.rag.domain.vo;

import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
/**
 * 知识库检索命中结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class KnowledgeSearchHitVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分块ID
     */
    private Long chunkId;

    /**
     * 所属文档ID
     */
    private Long documentId;

    /**
     * 来源文件名
     */
    private String fileName;

    /**
     * 分块序号
     */
    private Integer chunkIndex;

    /**
     * 分块文本内容
     */
    private String content;

    /**
     * 相关度得分(COSINE 相似度/RRF 融合分,越大越相关)
     */
    private Double score;

    /**
     * 向量通道得分(COSINE 相似度,0~1;未被向量通道命中为空)
     */
    private Double vectorScore;

    /**
     * 关键词通道得分(MySQL 全文相关度,非归一化;未被关键词通道命中为空)
     */
    private Double keywordScore;

    /**
     * RRF 融合得分(未开启精排时与 score 一致)
     */
    private Double rrfScore;

    /**
     * 命中通道(vector 向量 / keyword 关键词 / both 双通道)
     */
    private String matchChannels;

    /**
     * 分块是否启用(0 禁用 1 启用)
     */
    private Integer enabled;

    /**
     * 重排序精排得分(开启 rerank 时由模型打分产生,未开启为空)
     */
    private Double rerankScore;

    /**
     * 来源定位标记(如 page=3/sheet=销售表,便于前端跳转原文位置)
     */
    private String pageLabel;

    /**
     * 父块全文(开启父子分块时返回,供注入完整上下文;未开启为 null)
     */
    private String parentContent;

    /**
     * {@link #score} 的口径(cosine/rrf/rerank/bm25)。
     *
     * <p>三种分的量纲完全不同:RRF 只在本次 query 内可比(≈0.016~0.033),
     * 余弦是 -1~1,精排是 0~1。没有口径就无法判断阈值配得对不对。</p>
     */
    private String scoreType;

    /**
     * 归一化后的向量分((cos+1)/2,0~1),用于跨库比较与前端展示;非向量命中为 null
     */
    private Double normVectorScore;

    /**
     * 归一化后的关键词分(0~1,按本次候选集 min-max);仅用于展示
     */
    private Double normKeywordScore;

    /**
     * 本次返回结果内的名次(1 起),评测按名次判定与跨库融合都依赖它
     */
    private Integer rank;

}
