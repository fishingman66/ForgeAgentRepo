package com.forgeagent.common.mybatis.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
/**
 * SQL 日志配置。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@Data
@ConfigurationProperties(prefix = "mybatis-plus.sql-log")
public class SqlLogProperties {

    /**
     * 是否开启完整 SQL 输出。
     */
    private Boolean enabled = false;

    /**
     * 输出方式，可选 console、log。
     */
    private String output = "console";

    private List<String> ignoreTables = new ArrayList<>();
}
