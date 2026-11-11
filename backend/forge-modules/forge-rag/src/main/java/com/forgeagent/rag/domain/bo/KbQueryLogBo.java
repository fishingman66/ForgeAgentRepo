package com.forgeagent.rag.domain.bo;

import com.forgeagent.rag.service.retrieve.SearchOutcome;
import lombok.Data;

import java.util.List;

/**
 * 检索日志记录入参
 *
 * <p>直接带上 {@link SearchOutcome}:日志要记的就是这次检索的回执,
 * 拆成十个参数既容易漏字段,也会让"记的和返回的"慢慢不一致。</p>
 *
 * @author ForgeAgent
 */
@Data
public class KbQueryLogBo {

    private Long agentId;

    private Long sessionId;

    private Long userId;

    /**
     * 用户原始问题(未改写)——聚合"用户怎么问的"以它为准
     */
    private String originalQuery;

    /**
     * 实际送检的查询(改写后;未改写时与 originalQuery 相同)
     */
    private String retrievalQuery;

    private List<Long> knowledgeIds;

    private SearchOutcome outcome;
}
