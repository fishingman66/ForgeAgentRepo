package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库检索评测用例 agent_kb_eval_case
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_eval_case")
public class AgentKbEvalCase implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
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
     * 期望命中的文档名(模糊包含,空=不校验;legacy 口径,与新证据标注并存)
     */
    private String expectedDocName;

    /**
     * 期望命中内容包含的关键词(逗号分隔,任一命中即可,空=不校验;legacy 口径)
     */
    private String expectedKeywords;

    /**
     * 证据标注 JSON:[{"documentId":123,"span":"原文片段"}];空=走 legacy 口径判定
     */
    private String evidences;

    /**
     * 状态:ACTIVE / EVIDENCE_STALE(文档换代后证据不再命中) / DEPRECATED
     */
    private String status;

    /**
     * 来源:MANUAL / DEBUG_PAGE(调试页一键生成) / ZERO_HIT(零命中转化)
     */
    private String source;

    /**
     * 来源记录ID(如查询日志ID/分块ID)
     */
    private Long sourceId;

    /**
     * 数据集修订号(变更标注时递增,参与门禁可比性判定)
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
