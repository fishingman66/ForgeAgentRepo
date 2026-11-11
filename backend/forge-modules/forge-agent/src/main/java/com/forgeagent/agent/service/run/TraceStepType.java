package com.forgeagent.agent.service.run;

/**
 * Run/Trace 步骤类型常量(体检 F1:此前以字符串散落在 20+ 个调用点,
 * 建表注释漂移 —— WORKFLOW/ROUTER 未入注释,TOOL/FINISH 从未写入)。
 *
 * <p>写入侧唯一取值来源;统计与前端渲染以此为准。</p>
 *
 * @author ForgeAgent
 */
public final class TraceStepType {

    /** P2/C1:意图路由决策 */
    public static final String ROUTER = "ROUTER";
    /** 上下文装配(含 Prompt 模板编译) */
    public static final String CONTEXT = "CONTEXT";
    /** RAG 检索(改写 + 混合召回) */
    public static final String RETRIEVAL = "RETRIEVAL";
    /** 长期记忆注入 */
    public static final String MEMORY = "MEMORY";
    /** 问数语义路由与数据源上下文 */
    public static final String SEMANTIC = "SEMANTIC";
    /** Token 预算预检/拦截 */
    public static final String BUDGET = "BUDGET";
    /** LLM 执行(ReAct 内核整轮完成) */
    public static final String LLM = "LLM";
    /** 团队接力各棒 */
    public static final String TEAM = "TEAM";
    /** P2/W10:工作流执行链(节点事件与汇总) */
    public static final String WORKFLOW = "WORKFLOW";
    /** 执行异常 */
    public static final String ERROR = "ERROR";

    private TraceStepType() {
    }
}
