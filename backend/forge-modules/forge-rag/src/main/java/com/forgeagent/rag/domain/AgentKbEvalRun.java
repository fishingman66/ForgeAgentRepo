package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库检索评测运行记录 agent_kb_eval_run
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_eval_run")
public class AgentKbEvalRun implements Serializable {

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
     * 评测时的召回条数
     */
    private Integer topK;

    /**
     * 用例总数
     */
    private Integer totalCases;

    /**
     * 命中用例数
     */
    private Integer hitCases;

    /**
     * 平均倒数排名(MRR,0~1)
     */
    private Double mrr;

    /**
     * 命中用例的平均相关度分
     */
    private Double avgScore;

    /**
     * 评测耗时(毫秒)
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private Date createTime;
}
