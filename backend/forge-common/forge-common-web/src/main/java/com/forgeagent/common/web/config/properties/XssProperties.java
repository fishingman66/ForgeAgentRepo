package com.forgeagent.common.web.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * XSS 过滤配置属性，用于控制过滤器开关及排除路径。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@Data
@ConfigurationProperties(prefix = "xss")
public class XssProperties {

    /**
     * XSS 过滤总开关。
     */
    private Boolean enabled;

    /**
     * 跳过 XSS 过滤的请求路径集合。
     */
    private List<String> excludeUrls = new ArrayList<>();

}
