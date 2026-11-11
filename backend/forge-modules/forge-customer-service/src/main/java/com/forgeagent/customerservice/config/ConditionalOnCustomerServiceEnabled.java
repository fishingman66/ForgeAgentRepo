package com.forgeagent.customerservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 智能客服模块开关组合注解(S0)。
 *
 * <p>所有客服 Controller / 配置类统一标注;{@code agent.customer-service.enabled=false}
 * 时不产生任何客服端点与后台任务。DDL 与开关解耦,表存在不影响主应用启动。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "agent.customer-service", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnCustomerServiceEnabled {
}
