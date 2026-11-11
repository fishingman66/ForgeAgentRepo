package com.forgeagent.common.excel.annotation;

import com.forgeagent.common.excel.core.ExcelOptionsProvider;

import java.lang.annotation.*;

/**
 * Excel动态下拉选项注解
 *
 * @author Angus; 改造: 智造 ForgeAgent 2026-11
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ExcelDynamicOptions {

    /**
     * 提供者类全限定名
     * <p>
     * {@link com.forgeagent.common.excel.core.ExcelOptionsProvider} 接口实现类 class
     */
    Class<? extends ExcelOptionsProvider> providerClass();
}
