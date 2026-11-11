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
