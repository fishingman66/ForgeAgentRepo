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
     * 期望命中的文档名(模糊包含,空=不校验)
     */
    private String expectedDocName;

    /**
     * 期望命中内容包含的关键词(逗号分隔,任一命中即可,空=不校验)
     */
    private String expectedKeywords;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private Date createTime;
}
