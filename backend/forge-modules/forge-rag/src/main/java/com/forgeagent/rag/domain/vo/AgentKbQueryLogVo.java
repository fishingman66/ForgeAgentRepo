package com.forgeagent.rag.domain.vo;

import com.forgeagent.rag.domain.AgentKbQueryLog;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 知识库检索日志视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbQueryLog.class)
public class AgentKbQueryLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long agentId;

    /**
     * Agent 名称(展示用,由服务层批量补全)
     */
    private String agentName;

    private Long sessionId;

    private Long userId;

    private String knowledgeIds;

    private String queryText;

    private String rewrittenQuery;

    private String queryHash;

    private Integer resultCount;

    private Integer zeroHit;

    private BigDecimal top1Score;

    private String scoreType;

    private Integer vectorHits;

    private Integer keywordHits;

    /**
     * 降级原因原始串(逗号分隔)
     */
    private String degraded;

    /**
     * 降级原因列表(服务层把逗号串拆成数组,前端直接渲染标签)
     */
    private java.util.List<String> degradedReasons;

    private Integer retrievalMs;

    private Date createTime;
}
