package com.forgeagent.rag.mcp;

import java.util.Optional;

/**
 * 08方案 / A5 增强:对外 MCP 令牌解析 SPI。
 *
 * <p>/mcp 端点承载在 forge-rag,但客户端注册表属于平台域(agent 模块)。为了不让 rag
 * 反向依赖 agent,这里只声明解析能力,实现由 agent 侧注入(与
 * {@link McpExternalToolProvider} 同一模式)。实现方负责缓存与过期判定。</p>
 *
 * <p>无实现或全部返回 empty 时,端点回落到 {@code rag.knowledge.mcp-token} 单令牌
 * 常量时间比对(平台全量只读),保证既有部署零行为变更。</p>
 *
 * @author ForgeAgent
 */
public interface McpClientAuthenticator {

    /**
     * 按明文令牌解析调用方身份。
     *
     * @return 有效令牌返回身份;未知/停用/过期返回 {@link Optional#empty()}
     */
    Optional<McpCaller> authenticate(String token);

    /**
     * 该令牌是否归本实现管辖(只看格式,不做校验)。
     *
     * <p>端点用它区分两种 empty:不属于我的令牌要留给旧单 token 回落;
     * 属于我的但没通过(停用/过期/轮转)必须**直接拒绝**,绝不能回落成
     * 「不校验即放行」——否则停用等于没停。</p>
     */
    default boolean managed(String token) {
        return false;
    }
}
