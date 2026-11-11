package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSemanticValue;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 问数语义枚举值字典视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSemanticValue.class)
public class AgentSemanticValueVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long datasourceId;

    private String tableName;

    private String columnName;

    private String rawValue;

    private String displayName;

    private String synonyms;

    private Integer sort;

    private String status;

    private Date createTime;

    private Date updateTime;

}
