package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** FAQ 与补录答案 agent_cs_knowledge_answer(C2),含审核状态与生效期。 */
@Data
@TableName("agent_cs_knowledge_answer")
public class CsKnowledgeAnswer {
    @TableId private Long id;
    private String question, questionHash, answer, source, status, auditNote;
    /** B3 生成元数据:引用(JSON)与置信度,与人工审核备注分列。 */
    private String citations;
    private java.math.BigDecimal confidence;
    private Long workspaceId, knowledgeId, sourceRefId, auditBy;
    private Date effectiveFrom, effectiveTo, createTime;
}
