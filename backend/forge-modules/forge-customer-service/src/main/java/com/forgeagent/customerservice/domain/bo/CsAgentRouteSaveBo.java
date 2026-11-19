package com.forgeagent.customerservice.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsAgentRoute;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 智能路由规则业务对象(运营台新建/编辑)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsAgentRoute.class, reverseConvertGenerate = false)
public class CsAgentRouteSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "规则ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 渠道编码(空=全渠道)
     */
    @Size(max = 64, message = "渠道编码不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String channelCode;

    /**
     * 意图编码(路由业务键,大写)
     */
    @NotBlank(message = "意图编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "意图编码不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String intentCode;

    /**
     * 目标 Agent ID
     */
    @NotNull(message = "目标 Agent 不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long agentId;

    /**
     * 优先级(小者优先)
     */
    private Integer priority;

    /**
     * 是否启用(0否 1是)
     */
    private String enabled;
}
