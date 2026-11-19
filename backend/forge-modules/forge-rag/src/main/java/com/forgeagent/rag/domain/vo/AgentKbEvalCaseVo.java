package com.forgeagent.rag.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKbEvalCase;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库评测用例视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbEvalCase.class)
public class AgentKbEvalCaseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属知识库ID
     */
    private Long knowledgeId;

    /**
     * 评测问题
     */
    private String question;

    /**
     * 期望命中文档名(legacy 口径)
     */
    private String expectedDocName;

    /**
     * 期望关键词(legacy 口径,逗号分隔)
     */
    private String expectedKeywords;

    /**
     * 证据标注 JSON(span/document 级判定依据;空=legacy 用例)
     */
    private String evidences;

    /**
     * 用例状态:ACTIVE / EVIDENCE_STALE / DEPRECATED
     */
    private String status;

    /**
     * 用例来源:MANUAL / CASE_FAIL / DEBUG_PAGE 等
     */
    private String source;

    /**
     * 来源记录ID(质量问题/调试命中)
     */
    private Long sourceId;

    /**
     * 数据集修订号
     */
    private String datasetRevision;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
