package com.forgeagent.common.push.annotation;

import com.forgeagent.common.push.condition.MessageTransportCondition;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * 按消息推送传输方式启用组件。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(MessageTransportCondition.class)
public @interface ConditionalOnMessageTransport {

    /**
     * 传输方式：sse / websocket。
     */
    String value();

}
