package com.forgeagent.workflow.config;

import com.forgeagent.workflow.common.ConditionalOnEnable;
import org.springframework.context.annotation.Configuration;

/**
 * WarmFlow 工作流配置入口，在工作流开关开启时注册相关组件。
 *
 * @author may; 改造: 智造 ForgeAgent 2026-11
 */
@ConditionalOnEnable
@Configuration
public class WarmFlowConfig {

}

