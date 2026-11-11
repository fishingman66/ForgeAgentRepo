package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作空间业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentWorkspace.class, reverseConvertGenerate = false)
public class AgentWorkspaceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "空间ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 空间名称
     */
    @NotBlank(message = "空间名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 30, message = "空间名称长度须在 2-30 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 空间描述
     */
    @Size(max = 200, message = "空间描述不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 归属部门ID(可选;不传默认取创建者部门,编辑时传值即转移归属)
     */
    private Long deptId;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 30, groups = {QueryGroup.class})
    private String keyword;

}
