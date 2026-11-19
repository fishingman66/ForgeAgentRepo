package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据源可查询表清单项视图对象(名称 + 注释,供白名单选择)
 *
 * @author ForgeAgent
 */
@Data
public class AgentDatasourceTableVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表名
     */
    private String name;

    /**
     * 表注释(无注释为 null)
     */
    private String comment;

}
