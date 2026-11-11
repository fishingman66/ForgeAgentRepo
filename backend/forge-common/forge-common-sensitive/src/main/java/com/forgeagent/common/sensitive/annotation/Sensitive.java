package com.forgeagent.common.sensitive.annotation;

import com.forgeagent.common.sensitive.core.SensitiveStrategy;

import java.lang.annotation.*;

/**
 * 数据脱敏注解
 *
 * @author zhujie; 改造: 智造 ForgeAgent 2026-11
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Documented
public @interface Sensitive {

    /**
     * 脱敏策略。
     *
     * @return 脱敏策略
     */
    SensitiveStrategy strategy();

    /**
     * 角色标识符 多个角色满足一个即可
     */
    String[] roleKey() default {};

    /**
     * 权限标识符 多个权限满足一个即可
     */
    String[] perms() default {};
}
