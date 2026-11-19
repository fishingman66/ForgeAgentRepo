package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentEvalDataset;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 评测数据集视图对象(含样本计数派生列)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentEvalDataset.class)
public class AgentEvalDatasetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long workspaceId;

    private Long agentId;

    private String name;

    private String description;

    private Date createTime;

    /** 样本总数(count 子查询派生列) */
    private Integer caseCount;
}
