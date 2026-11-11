package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库质量问题 agent_kb_quality_issue(R3-5 轻量版 3 态)
 *
 * <p>不做参考项目的四阶段 + 流水表 + 回归验证器:单团队运营,
 * 等出现第一个"修了没修好"争议再补。</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_quality_issue")
public class AgentKbQualityIssue implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    /**
     * 触发问题的查询原文
     */
    private String queryText;

    /**
     * 查询指纹(与 agent_kb_query_log.query_hash 同口径)
     */
    private String queryHash;

    /**
     * 原因:MISSING_KNOWLEDGE / OUTDATED_KNOWLEDGE / RETRIEVAL_MISS / ANSWER_INCORRECT / OTHER
     */
    private String reason;

    /**
     * 来源:ZERO_HIT(零命中日志一键登记) / DEBUG_PAGE(调试页) / MANUAL / CASE_FAIL(评测失败转化)
     */
    private String source;

    /**
     * 来源记录ID
     */
    private Long sourceId;

    /**
     * 负责人
     */
    private String owner;

    /**
     * 关联评测用例ID(修复后补建用例做回归)
     */
    private Long linkedCaseId;

    /**
     * 状态:NEW / IN_PROGRESS / RESOLVED
     */
    private String status;

    /**
     * 处理备注
     */
    private String note;

    private Date createTime;

    private Date updateTime;

    /**
     * 解决时间(RESOLVED 时落)
     */
    private Date resolveTime;

    /**
     * 乐观锁(每次状态变更 +1)
     */
    private Integer revision;
}
