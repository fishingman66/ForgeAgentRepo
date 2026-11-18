package com.forgeagent.customerservice.config;

import com.forgeagent.callcenter.common.sentiment.LexiconSentimentAnalyzer;
import com.forgeagent.callcenter.common.sentiment.SentimentAnalyzer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能客服模块装配入口(S0)。
 *
 * <p>{@code ForgeAgentApplication} 位于根包 {@code com.forgeagent},本模块
 * {@code com.forgeagent.customerservice.*} 的组件与 Mapper 已被通配扫描覆盖
 * (mybatis-plus.mapperPackage = com.forgeagent.**.mapper),无需额外 ComponentScan。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnCustomerServiceEnabled
@EnableConfigurationProperties(CsModuleProperties.class)
public class CsModuleConfiguration {

    /**
     * B1:文本侧情绪分析——复用上移到 callcenter-common 的词表实现(与语音洞察同一口径)。
     * @ConditionalOnMissingBean 允许语音模块或后续 LLM 实现按优先级接管,同一容器只留一个。
     */
    @Bean
    @ConditionalOnMissingBean(SentimentAnalyzer.class)
    public SentimentAnalyzer csSentimentAnalyzer() {
        return new LexiconSentimentAnalyzer();
    }
}
