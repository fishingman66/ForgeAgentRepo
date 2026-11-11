package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 问数示例库对象 agent_datasource_example
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_datasource_example")
public class AgentDatasourceExample extends BaseEntity {

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
     * 示例问题
     */
    private String question;

    /**
     * 示例SQL
     */
    private String sqlContent;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;

}
