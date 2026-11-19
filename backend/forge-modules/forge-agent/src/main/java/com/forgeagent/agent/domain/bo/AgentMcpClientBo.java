package com.forgeagent.agent.domain.bo;

import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对外 MCP 客户端表单对象(08方案 / A5 增强)
 *
 * @author ForgeAgent
 */
@Data
public class AgentMcpClientBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    @NotBlank(message = "客户端名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "名称不能超过 64 字符")
    private String name;

    /** 绑定工作空间;为空表示平台全量只读 */
    private Long workspaceId;

    /** 授权域,逗号分隔(knowledge,skill);为空表示全部 */
    @Size(max = 200, message = "授权域不能超过 200 字符")
    private String scopes;

    /** 过期时间,支持 "yyyy-MM-dd HH:mm:ss" 或 ISO-8601;为空表示长期有效 */
    private String expireTime;

    @Size(max = 200, message = "备注不能超过 200 字符")
    private String remark;
}
