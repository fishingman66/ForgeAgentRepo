package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSemanticDimension;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 问数语义维度业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSemanticDimension.class, reverseConvertGenerate = false)
public class AgentSemanticDimensionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "ID不能为空", groups = {EditGroup.class})
    private Long id;

    @NotNull(message = "数据源ID不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long datasourceId;

    @NotBlank(message = "表名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "表名不能超过 128 字符", groups = {AddGroup.class, EditGroup.class})
    private String tableName;

    @NotBlank(message = "列名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "列名不能超过 128 字符", groups = {AddGroup.class, EditGroup.class})
    private String columnName;

    @NotBlank(message = "业务显示名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "业务显示名不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String displayName;

    @Size(max = 500, message = "同义词不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String synonyms;

    @Size(max = 20, message = "维度类型不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String dimType;

    private Integer isTime;

    @Size(max = 50, message = "时间格式不能超过 50 字符", groups = {AddGroup.class, EditGroup.class})
    private String timeFormat;

    @Size(max = 500, message = "说明不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    private Integer sort;

    private String status;

}
