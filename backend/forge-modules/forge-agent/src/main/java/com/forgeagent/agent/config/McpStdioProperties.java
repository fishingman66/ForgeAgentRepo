package com.forgeagent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 / C2:MCP STDIO 传输的准入配置。
 *
 * <p>STDIO 会以 JVM 身份在本机拉起子进程,是比 http 更危险的能力,因此默认
 * <b>完全禁用</b>——{@code allowed-commands} 为空即拒绝一切 stdio 配置(而非只拒绝未列出的命令)。</p>
 *
 * <p>白名单项可写命令名({@code node})或绝对路径({@code /usr/local/bin/node}),
 * 校验时按「文件名」与「完整字符串」双口径比对。</p>
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.mcp.stdio")
public class McpStdioProperties {

    /** 允许拉起的可执行命令白名单;为空表示禁用 stdio 传输 */
    private List<String> allowedCommands = new ArrayList<>();

    /**
     * 是否允许解释器的内联代码开关({@code -c} / {@code -e} / {@code --eval})。
     * 默认禁止:否则 {@code python -c "任意代码"} 可绕过命令白名单实现任意执行。
     */
    private boolean allowInlineCode = false;

    /**
     * C2:命令 → 允许的启动参数正则列表。仅对列出的命令生效,未列出的命令不额外限制
     * (保持存量配置兼容)。用于收紧 {@code npx -y <任意包>} 这类"命令白名单挡不住"的逃逸面,
     * 例如 {@code npx: ["^-y$", "^@scope/allowed-.*$"]} 表示参数须逐条命中其中一条正则。
     *
     * <p>匹配口径:每个参数都必须至少命中一条正则({@code -y} 这类开关也要显式列出)。
     * 正则由运维配置,编译期由 validator 做 Pattern 合法性校验,非法正则拒绝配置。</p>
     */
    private Map<String, List<String>> argsPatterns = new HashMap<>();
}
