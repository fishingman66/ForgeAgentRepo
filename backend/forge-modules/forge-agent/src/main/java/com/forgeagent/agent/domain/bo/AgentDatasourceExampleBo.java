package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentDatasourceExample;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 问数示例业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentDatasourceExample.class, reverseConvertGenerate = false)
public class AgentDatasourceExampleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "示例ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 数据源ID
     */
    @NotNull(message = "数据源ID不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long datasourceId;

    /**
     * 示例问题
     */
    @NotBlank(message = "示例问题不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 500, message = "示例问题不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String question;

    /**
     * 示例SQL
     */
    @NotBlank(message = "示例SQL不能为空", groups = {AddGroup.class, EditGroup.class})
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
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

}
