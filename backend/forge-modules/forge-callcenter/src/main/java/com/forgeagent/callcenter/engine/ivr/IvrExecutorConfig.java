package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.config.CallcenterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IVR 节点执行器装配(C3):9 个执行器以 @Bean 注册,注册表自动发现;
 * intention/agent 的分类与问答实现由 Spring 注入(未来 LLM/RAG 实现注册为
 * 高优先级 Bean 即可接管)。
 */
@Configuration
@RequiredArgsConstructor
public class IvrExecutorConfig {

    private final CallcenterProperties properties;

    @Bean
    public NodeExecutor startExecutor() {
        return new IvrExecutors.StartExecutor();
    }

    @Bean
    public NodeExecutor sayExecutor() {
        return new IvrExecutors.SayExecutor();
    }

    @Bean
    public NodeExecutor answerExecutor() {
        return new IvrExecutors.AnswerExecutor();
    }

    @Bean
    public NodeExecutor receivedExecutor() {
        return new IvrExecutors.ReceivedExecutor();
    }

    @Bean
    public NodeExecutor intentionExecutor(IntentionClassifier classifier) {
        return new IvrExecutors.IntentionExecutor(() -> classifier);
    }

    @Bean
    public NodeExecutor agentExecutor(KnowledgeAnswerer answerer) {
        return new IvrExecutors.AgentExecutor(() -> answerer);
    }

    @Bean
    public NodeExecutor conditionExecutor() {
        return new IvrExecutors.ConditionExecutor();
    }

    @Bean
    public NodeExecutor transferExecutor() {
        return new IvrExecutors.TransferExecutor();
    }

    @Bean
    public NodeExecutor hangupExecutor() {
        return new IvrExecutors.HangupExecutor();
    }
}
