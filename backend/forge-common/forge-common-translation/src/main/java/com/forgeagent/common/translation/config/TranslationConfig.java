package com.forgeagent.common.translation.config;

import com.forgeagent.common.translation.core.TranslationInterface;
import com.forgeagent.common.translation.core.handler.TranslationJsonFieldProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 翻译模块配置类
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@AutoConfiguration
public class TranslationConfig {

    /**
     * 创建翻译 JSON 字段处理器。
     *
     * @param list 翻译实现集合
     * @return 翻译 JSON 字段处理器
     */
    @Bean
    public TranslationJsonFieldProcessor translationJsonFieldProcessor(List<TranslationInterface<?>> list) {
        return new TranslationJsonFieldProcessor(list);
    }

}
