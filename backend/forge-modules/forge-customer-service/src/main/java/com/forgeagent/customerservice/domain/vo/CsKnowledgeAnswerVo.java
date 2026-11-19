package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识答案视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsKnowledgeAnswer.class)
public class CsKnowledgeAnswerVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String question;
    private String questionHash;
    private String answer;
    private String source;
    private String status;
    private String auditNote;
    private Long workspaceId;
    private Long knowledgeId;
    private Long sourceRefId;
    private Long auditBy;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Date createTime;
}
