package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 问数语义指标对象 agent_semantic_metric
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_semantic_metric")
public class AgentSemanticMetric extends BaseEntity {

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
     * 指标名
     */
    private String name;

    /**
     * 同义词(逗号分隔)
     */
    private String synonyms;

    /**
     * 基表名
     */
    private String baseTable;

    /**
     * 可执行表达式,如 SUM(pay_amount)
     */
    private String expression;

    /**
     * 默认过滤条件
     */
    private String defaultFilters;

    /**
     * 单位
     */
    private String unit;

    /**
     * 展示格式
     */
    private String format;

    /**
     * 口径说明
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
