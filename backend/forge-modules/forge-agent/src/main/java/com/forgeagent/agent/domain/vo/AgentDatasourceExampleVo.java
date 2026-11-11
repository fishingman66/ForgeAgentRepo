package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentDatasourceExample;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 问数示例视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentDatasourceExample.class)
public class AgentDatasourceExampleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
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
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
