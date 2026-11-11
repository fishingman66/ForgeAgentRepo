package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 问数语义维度对象 agent_semantic_dimension
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_semantic_dimension")
public class AgentSemanticDimension extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 数据源ID
     */
    private Long datasourceId;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 列名
     */
    private String columnName;

    /**
     * 业务显示名
     */
    private String displayName;

    /**
     * 同义词(逗号分隔)
     */
    private String synonyms;

    /**
     * 维度类型(enum/time/id/text)
     */
    private String dimType;

    /**
     * 是否默认时间维度(0否 1是)
     */
    private Integer isTime;

    /**
     * 时间粒度格式,如 %Y-%m
     */
    private String timeFormat;

    /**
     * 说明
     */
    private String description;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 删除标志
     */
    @TableLogic
    private Long delFlag;

}
