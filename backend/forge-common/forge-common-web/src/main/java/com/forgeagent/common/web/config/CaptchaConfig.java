package com.forgeagent.common.web.config;

import com.forgeagent.common.web.config.properties.CaptchaProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 验证码配置
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@AutoConfiguration
@EnableConfigurationProperties(CaptchaProperties.class)
public class CaptchaConfig {

}
