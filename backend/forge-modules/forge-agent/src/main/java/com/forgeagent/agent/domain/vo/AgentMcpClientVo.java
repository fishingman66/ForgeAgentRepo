package com.forgeagent.agent.domain.vo;

import com.forgeagent.agent.domain.AgentMcpClient;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对外 MCP 客户端视图对象(08方案 / A5 增强)。
 *
 * <p>不含 {@code tokenHash},只回 {@code tokenPrefix} 供人识别;明文令牌见
 * {@link AgentMcpClientTokenVo},仅创建与轮转时返回一次。</p>
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMcpClient.class)
public class AgentMcpClientVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 令牌前缀(如 fgm_ab12cd) */
    private String tokenPrefix;

    /** 绑定工作空间(NULL=平台全量只读) */
    private Long workspaceId;

    /** 授权域(knowledge,skill;空=全部) */
    private String scopes;

    /** 状态(0启用 1停用) */
    private String enabled;

    private LocalDateTime expireTime;

    private LocalDateTime lastUsedTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
