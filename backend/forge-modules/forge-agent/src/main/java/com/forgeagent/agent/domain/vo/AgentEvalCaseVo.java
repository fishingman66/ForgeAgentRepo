package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentEvalCase;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 评测样本视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentEvalCase.class)
public class AgentEvalCaseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long datasetId;

    private String question;

    private String expectedKeywords;

    private String referenceAnswer;

    /** 期望拒答:0/1 */
    private Integer expectedRefusal;

    /** manual / feedback(评测回流) */
    private String source;

    /** 回流来源(会话/badcase id) */
    private Long sourceRefId;

    /** '0'待确认 / '1'已确认;历史样本视为已过 */
    private String reviewStatus;

    private Date createTime;
}
