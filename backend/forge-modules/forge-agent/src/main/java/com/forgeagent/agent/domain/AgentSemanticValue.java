package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 问数语义枚举值字典对象 agent_semantic_value
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_semantic_value")
public class AgentSemanticValue extends BaseEntity {

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
     * 库内真实值
     */
    private String rawValue;

    /**
     * 业务显示名
     */
    private String displayName;

    /**
     * 别名(逗号分隔)
     */
    private String synonyms;

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
