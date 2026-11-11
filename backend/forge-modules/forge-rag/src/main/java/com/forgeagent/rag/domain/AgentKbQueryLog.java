package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 知识库检索日志 agent_kb_query_log
 *
 * <p>每次对话检索落一行,回答的问题是"这次检索走成什么样":召回了多少、首条分多少、
 * 用的什么分数口径、有没有降级、耗时多少。零命中日志只记"没命中",排障时会缺一半信息。</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_query_log")
public class AgentKbQueryLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Agent ID(客服/工作流链路可能为空)
     */
    private Long agentId;

    private Long sessionId;

    private Long userId;

    /**
     * 参与检索的知识库ID(逗号分隔,多库一次检索只落一行)
     */
    private String knowledgeIds;

    /**
     * 用户原始问题(未改写)
     */
    private String queryText;

    /**
     * 实际送检的查询(改写后;未改写时与原始问题一致)
     */
    private String rewrittenQuery;

    /**
     * 查询指纹:trim + 小写 + 空白折叠后的 md5,用于聚合"同一类问题"
     */
    private String queryHash;

    private Integer resultCount;

    /**
     * 是否零命中(1/0),落库时派生,便于直接按缺口筛选
     */
    private Integer zeroHit;

    /**
     * 首条分数(按 scoreType 解释)
     */
    private BigDecimal top1Score;

    /**
     * 首条分数的口径(cosine/rrf/rerank/bm25)
     */
    private String scoreType;

    /**
     * 向量路召回条数(过滤前)
     */
    private Integer vectorHits;

    /**
     * 关键词路召回条数(过滤前)
     */
    private Integer keywordHits;

    /**
     * 降级原因(逗号分隔的 code,空表示全链路正常)
     */
    private String degraded;

    private Integer retrievalMs;

    private Date createTime;
}
