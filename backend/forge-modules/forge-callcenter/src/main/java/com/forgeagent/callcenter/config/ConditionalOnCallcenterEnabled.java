package com.forgeagent.callcenter.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 语音模块开关组合注解(V1)。
 *
 * <p>与文本域 {@code agent.customer-service.enabled} 互相独立;
 * {@code agent.callcenter.enabled=false}（默认）时不产生任何语音端点与后台任务。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "agent.callcenter", name = "enabled",
    havingValue = "true", matchIfMissing = false)
public @interface ConditionalOnCallcenterEnabled {
}
