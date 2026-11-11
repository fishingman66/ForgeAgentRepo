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
    private Long workspaceId, knowledgeId, sourceRefId, auditBy;
    private Date effectiveFrom, effectiveTo, createTime;
}
