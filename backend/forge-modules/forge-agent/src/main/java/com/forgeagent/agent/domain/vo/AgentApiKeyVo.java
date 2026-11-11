package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentApiKey;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 空间 API Key 视图对象(脱敏)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentApiKey.class)
public class AgentApiKeyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属工作空间ID
     */
    private Long workspaceId;

    /**
     * 工作空间名称
     */
    private String workspaceName;

    /**
     * Key 名称
     */
    private String name;

    /**
     * Key 脱敏展示(前缀+****)
     */
    private String maskedKey;

    /**
     * 状态(0正常 1已吊销)
     */
    private String status;

    /**
     * 最后使用时间
     */
    private Date lastUsedTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 创建者
     */
    private Long createBy;

}
