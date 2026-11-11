package com.forgeagent.rag.domain.vo;

import com.forgeagent.rag.domain.AgentKbQualityIssue;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库质量问题视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbQualityIssue.class)
public class AgentKbQualityIssueVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long knowledgeId;

    private String queryText;

    private String queryHash;

    private String reason;

    private String source;

    private Long sourceId;

    private String owner;

    private Long linkedCaseId;

    private String status;

    private String note;

    private Date createTime;

    private Date updateTime;

    private Date resolveTime;

    private Integer revision;
}
