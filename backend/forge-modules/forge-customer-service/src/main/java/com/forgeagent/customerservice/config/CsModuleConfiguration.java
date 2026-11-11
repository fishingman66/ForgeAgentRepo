package com.forgeagent.customerservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
}
