package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSemanticMetric;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 问数语义指标视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSemanticMetric.class)
public class AgentSemanticMetricVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long datasourceId;

    private String name;

    private String synonyms;

    private String baseTable;

    private String expression;

    private String defaultFilters;

    private String unit;

    private String format;

    private String description;

    private Integer sort;

    private String status;

    private Date createTime;

    private Date updateTime;

}
