package com.forgeagent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行时各类超时/重试配置 —— 集中管理,上线后不用改源码就能调优
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {

    /** AgentScope Runner 单次 ReAct 调用最大阻塞秒数(含多轮工具循环) */
    private long scopeCallTimeoutSeconds = 600;

    /** ReAct 单轮最大推理-执行迭代数(1~50,越界回退 10);P0 起可配置而非硬编码 */
    private int scopeMaxIters = 10;

    /**
     * 是否启用 OpenAI 兼容直连兜底通道。
     * 默认关闭:AgentScopeRunner(Order 100)已覆盖全部 llmReady 场景,开启前该执行器不可达。
     */
    private boolean openAiCompatEnabled = false;

    /** OpenAI 兼容通道连接超时秒数 */
    private long openAiConnectTimeoutSeconds = 10;

    /** OpenAI 兼容通道请求总超时秒数 */
    private long openAiRequestTimeoutSeconds = 120;

    /** 长期记忆提取 LLM 调用超时秒数 */
    private long memoryExtractTimeoutSeconds = 60;

    /** RAG 查询改写 LLM 调用超时秒数 */
    private long ragRewriteTimeoutSeconds = 30;

    /** MCP Server initialize / listTools 单次阻塞秒数 */
    private long mcpInitTimeoutSeconds = 30;

    /** 工作流节点整体等待秒数(含内部 LLM / 工具) */
    private long workflowNodeTimeoutSeconds = 300;

    /**
     * 体检 S3:工作流 http 节点 SSRF 防护开关。
     * 开启时 DNS 解析校验目标地址(拒绝回环/私网/链路本地),禁用重定向;仅允许 http/https。
     */
    private boolean workflowHttpGuardEnabled = true;

    /**
     * D4:MCP 服务端地址的 SSRF 守护开关。默认开启(防御公网攻击面),
     * 合法内网 MCP 由 {@link #mcpSsrfAllowlist} 显式放行(默认已含回环与 RFC1918/ULA 常用段)。
     * 校验按 SsrfGuard 口径:DNS 解析拒绝回环/私网/链路本地/组播,allowlist 命中即放行。
     */
    private boolean mcpSsrfGuardEnabled = true;

    /**
     * D4:MCP SSRF 守护白名单(host 精确匹配或 CIDR 网段,如 {@code mcp.internal}、{@code 10.0.0.0/8})。
     * 默认放行回环与常用内网段,保证既有内网 MCP 部署不受默认开启影响;
     * <b>链路本地 169.254.0.0/16(云厂商元数据端点)与 CGNAT 100.64/10 默认不放行</b>,
     * 如确有需要由运维显式追加。置为空列表将导致启动失败(见 {@link #afterPropertiesSet}),
     * 避免"开了守护但白名单为空"的无效配置。
     */
    private java.util.List<String> mcpSsrfAllowlist = java.util.List.of(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7");

    @jakarta.annotation.PostConstruct
    void afterPropertiesSet() {
        if (mcpSsrfGuardEnabled && (mcpSsrfAllowlist == null || mcpSsrfAllowlist.isEmpty())) {
            throw new IllegalStateException(
                "agent.runtime.mcp-ssrf-guard-enabled=true 时必须配置 mcp-ssrf-allowlist"
                    + "(至少放行业务所需内网段);若确要全量拦截请直接关闭开关");
        }
    }

    /** 代码沙箱默认执行秒数(工作流内可覆盖) */
    private long sandboxDefaultTimeoutSeconds = 30;

    /** 问数 EXPLAIN 预检预估扫描行数熔断阈值(<=0 关闭预检) */
    private long sqlExplainMaxScanRows = 1_000_000;

    /** 语义路由:命中 Top-K */
    private int semanticTopK = 5;

    /** 语义路由:最低相似度阈值 */
    private double semanticMinScore = 0.4;

    /** 语义路由:歧义判定分差(小于此值视为歧义) */
    private double semanticAmbiguityMargin = 0.1;

    /** 语义注入:上下文字符预算上限(粗略按 1 中文=2 token 估算) */
    private int semanticContextChars = 3000;

    /** 语义注入:相似示例条数 */
    private int semanticExampleTopN = 3;
}
