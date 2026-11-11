package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentTrigger;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * Agent 触发器业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentTrigger.class, reverseConvertGenerate = false)
public class AgentTriggerBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "触发器ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 触发器名称
     */
    @NotBlank(message = "触发器名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "触发器名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 触发类型(cron/webhook)
     */
    @NotBlank(message = "触发类型不能为空", groups = {AddGroup.class, EditGroup.class})
    private String triggerType;

    /**
     * 目标类型(agent/workflow)
     */
    @NotBlank(message = "目标类型不能为空", groups = {AddGroup.class, EditGroup.class})
    private String targetType;

    /**
     * 目标ID(AgentID)
     */
    @NotNull(message = "目标不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long targetId;

    /**
     * 输入模板
     */
    @Size(max = 4000, message = "输入模板不能超过 4000 字符", groups = {AddGroup.class, EditGroup.class})
    private String inputs;

    /**
     * Cron 表达式(定时触发器必填)
     */
    @Size(max = 50, message = "Cron表达式不能超过 50 字符", groups = {AddGroup.class, EditGroup.class})
    private String cronExpr;

    /**
     * 是否启用(0启用 1停用)
     */
    private String enabled;

    /**
     * 备注
     */
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

    /**
     * 查询触发类型(仅查询用)
     */
    private String triggerTypeQuery;

}
