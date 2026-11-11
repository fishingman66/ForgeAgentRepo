package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSemanticMetric;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 问数语义指标业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSemanticMetric.class, reverseConvertGenerate = false)
public class AgentSemanticMetricBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "ID不能为空", groups = {EditGroup.class})
    private Long id;

    @NotNull(message = "数据源ID不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long datasourceId;

    @NotBlank(message = "指标名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "指标名不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String name;

    @Size(max = 500, message = "同义词不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String synonyms;

    @NotBlank(message = "基表名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "基表名不能超过 128 字符", groups = {AddGroup.class, EditGroup.class})
    private String baseTable;

    @NotBlank(message = "表达式不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 1000, message = "表达式不能超过 1000 字符", groups = {AddGroup.class, EditGroup.class})
    private String expression;

    @Size(max = 500, message = "默认过滤条件不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String defaultFilters;

    @Size(max = 20, message = "单位不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String unit;

    @Size(max = 50, message = "展示格式不能超过 50 字符", groups = {AddGroup.class, EditGroup.class})
    private String format;

    @Size(max = 500, message = "口径说明不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    private Integer sort;

    private String status;

}
