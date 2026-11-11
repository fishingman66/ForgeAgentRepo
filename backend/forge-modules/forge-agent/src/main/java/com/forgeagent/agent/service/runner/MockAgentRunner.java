package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.service.AgentRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Mock 执行器:Agent 未配置可用的模型接入时,回显用户消息与 Agent 配置
 *
 * <p>仅为本地联调兜底,不是真实推理。命中时打 WARN 明示,避免把回显结果误当作模型产出。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@Order(300)
public class MockAgentRunner implements AgentRunner {

    @Override
    public boolean supports(RunnerContext ctx) {
        boolean mock = !ctx.llmReady();
        if (mock) {
            log.warn("[MockAgentRunner] Agent[{}] 未配置可用模型接入,本轮以 Mock 回显执行(非真实推理)",
                ctx.agentName());
        }
        return mock;
    }

    @Override
    public RunnerResult reply(RunnerContext ctx, String content) {
        return new RunnerResult("【Mock 执行】收到你的消息:「" + content + "」\n\n"
            + "当前 Agent:" + ctx.agentName() + "\n"
            + "绑定模型:" + (ctx.modelName() == null ? "未绑定" : ctx.modelName()) + "(未配置模型接入信息,真实 LLM 配置后自动生效)\n"
            + "绑定工具:" + (ctx.toolNames() == null || ctx.toolNames().isEmpty() ? "无" : ctx.toolNames()) + "\n"
            + "绑定知识库:" + (ctx.knowledgeNames() == null || ctx.knowledgeNames().isEmpty() ? "无" : ctx.knowledgeNames()) + "\n"
            + "知识库检索:" + (ctx.retrievedContext() == null || ctx.retrievedContext().isEmpty()
            ? "未命中" : "命中,已检索到以下内容:\n" + ctx.retrievedContext()) + "\n"
            + "系统提示词:" + (ctx.systemPrompt() == null || ctx.systemPrompt().isEmpty()
            ? "未设置" : "已设置(" + ctx.systemPrompt().length() + " 字符)"),
            null, null, null, "mock");
    }
}
