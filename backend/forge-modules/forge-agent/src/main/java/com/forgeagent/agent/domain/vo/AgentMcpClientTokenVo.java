package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 令牌下发视图对象(08方案 / A5 增强)。
 *
 * <p>只在「创建客户端」与「轮转令牌」两个动作返回一次,之后服务端只留哈希,
 * 客户端配置进各自的 MCP client 后就再也取不回来——这是刻意的。</p>
 *
 * @author ForgeAgent
 */
@Data
public class AgentMcpClientTokenVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 明文令牌(仅此一次) */
    private String token;

    /** 令牌前缀(与列表页展示一致) */
    private String tokenPrefix;
}
