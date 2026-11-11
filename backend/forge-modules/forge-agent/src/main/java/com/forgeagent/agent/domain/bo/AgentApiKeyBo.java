package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentApiKey;
import com.forgeagent.common.core.validate.AddGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 空间 API Key 业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentApiKey.class, reverseConvertGenerate = false)
public class AgentApiKeyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属工作空间ID(创建必填)
     */
    @NotNull(message = "工作空间ID不能为空", groups = {AddGroup.class})
    private Long workspaceId;

    /**
     * Key 名称(创建必填)
     */
    @NotBlank(message = "Key 名称不能为空", groups = {AddGroup.class})
    @Size(max = 50, message = "Key 名称不能超过 50 字符", groups = {AddGroup.class})
    private String name;

    /**
     * 状态(0正常 1已吊销,查询用)
     */
    private String status;

}
