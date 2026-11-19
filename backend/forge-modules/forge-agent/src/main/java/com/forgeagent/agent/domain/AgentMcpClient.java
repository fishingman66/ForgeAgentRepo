package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 对外 MCP 客户端对象 agent_mcp_client(08方案 / A5 增强)。
 *
 * <p>令牌只存 SHA-256 与前缀,明文仅在创建/轮转时返回一次。{@code workspaceId}
 * 为空表示平台全量只读(与旧单 token 等义);{@code scopes} 为空表示全部授权域。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_mcp_client")
public class AgentMcpClient extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    /** 客户端名称(如 DSH / Claude Code) */
    private String name;

    /** 令牌 SHA-256(不存明文) */
    private String tokenHash;

    /** 令牌前缀(便于识别,不足以还原) */
    private String tokenPrefix;

    /** 绑定工作空间(NULL=平台全量只读) */
    private Long workspaceId;

    /** 授权域(knowledge,skill;空=全部) */
    private String scopes;

    /** 状态(0启用 1停用) */
    private String enabled;

    /** 过期时间(NULL=长期) */
    private LocalDateTime expireTime;

    /** 最近调用时间 */
    private LocalDateTime lastUsedTime;

    /** 备注 */
    private String remark;

    /** 删除标志 */
    @TableLogic
    private Long delFlag;
}
