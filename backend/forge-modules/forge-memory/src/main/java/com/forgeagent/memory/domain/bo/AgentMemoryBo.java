package com.forgeagent.memory.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.memory.domain.AgentMemory;

import java.io.Serial;
import java.io.Serializable;

/**
 * Agent 长期记忆业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMemory.class)
public class AgentMemoryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * AgentID
     */
    @NotNull(message = "AgentID不能为空")
    private Long agentId;

    /**
     * 记忆内容
     */
    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 1000, message = "记忆内容不能超过 1000 字符")
    private String content;

}
