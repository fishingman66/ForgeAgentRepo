package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSemanticDimension;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 问数语义维度视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSemanticDimension.class)
public class AgentSemanticDimensionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long datasourceId;

    private String tableName;

    private String columnName;

    private String displayName;

    private String synonyms;

    private String dimType;

    private Integer isTime;

    private String timeFormat;

    private String description;

    private Integer sort;

    private String status;

    private Date createTime;

    private Date updateTime;

}
