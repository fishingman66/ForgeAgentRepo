package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSession.class, reverseConvertGenerate = false)
public class AgentSessionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "会话ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * AgentID(新增必填)
     */
    @NotNull(message = "Agent不能为空", groups = {AddGroup.class})
    private Long agentId;

    /**
     * 会话标题
     */
    @Size(max = 100, message = "会话标题不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String title;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

}
