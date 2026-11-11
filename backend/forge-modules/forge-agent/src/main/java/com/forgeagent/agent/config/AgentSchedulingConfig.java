package com.forgeagent.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * agent 模块定时任务开关
 *
 * <p>框架 snail-job 未启用时 @EnableScheduling 未激活,
 * 本模块自建调度用于问数表结构快照定时刷新等轻量任务。</p>
 *
 * @author ForgeAgent
 */
@Configuration
@EnableScheduling
public class AgentSchedulingConfig {
}
