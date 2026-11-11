package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import com.forgeagent.common.core.validate.AddGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场安装对象(依赖检查/确认安装共用)
 *
 * @author ForgeAgent
 */
@Data
public class AgentMarketInstallBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 市场条目ID
     */
    @NotNull(message = "市场条目ID不能为空", groups = {AddGroup.class})
    private Long listingId;

    /**
     * 安装目标工作空间ID
     */
    @NotNull(message = "目标工作空间ID不能为空", groups = {AddGroup.class})
    private Long workspaceId;

}
