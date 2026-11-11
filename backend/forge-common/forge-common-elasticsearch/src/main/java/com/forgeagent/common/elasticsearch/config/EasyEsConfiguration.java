package com.forgeagent.common.elasticsearch.config;

import org.dromara.easyes.spring.annotation.EsMapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * easy-es 配置
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@AutoConfiguration
@ConditionalOnProperty(value = "easy-es.enable", havingValue = "true")
@EsMapperScan("com.forgeagent.**.esmapper")
public class EasyEsConfiguration {

}
