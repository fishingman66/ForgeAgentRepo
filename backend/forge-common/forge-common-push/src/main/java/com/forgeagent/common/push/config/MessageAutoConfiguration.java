package com.forgeagent.common.push.config;

import com.forgeagent.common.push.properties.MessageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 统一消息推送公共自动装配。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "message", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MessageProperties.class)
public class MessageAutoConfiguration {
}
