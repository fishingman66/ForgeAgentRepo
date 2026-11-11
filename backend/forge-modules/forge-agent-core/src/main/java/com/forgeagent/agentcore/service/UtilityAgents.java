package com.forgeagent.agentcore.service;

import com.forgeagent.agentcore.domain.AgentModel;

/**
 * 工具型 ReActAgent 工厂(标题生成 / 查询改写等轻量 LLM 调用复用)
 *
 * @author ForgeAgent
 */
public final class UtilityAgents {

    private UtilityAgents() {
    }

    /**
     * 构建工具型 ReActAgent(小 maxIters,不做工具调用,仅用于一次性文本任务)
     */
    public static io.agentscope.core.ReActAgent build(String name, String sysPrompt, AgentModel model) {
        io.agentscope.core.model.Model asModel = AgentScopeModelFactory.build(
            model.getBaseUrl(), model.getApiKey(), model.getModelName(), null, null);
        return io.agentscope.core.ReActAgent.builder()
            .name(name)
            .model(asModel)
            .maxIters(2)
            .sysPrompt(sysPrompt)
            .build();
    }
}