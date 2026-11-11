package com.forgeagent.customerservice.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产就绪校验(C0):移植参考项目「静默降级比启动失败更难查」的经验。
 *
 * <p>prod profile 下访客令牌仍用开发默认密钥时拒绝启动;dev 只告警不阻断,
 * 保证本地开发开箱可用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsProductionReadinessValidator {

    private static final String DEV_SECRET = "dev-only-change-me-before-production";

    private final CsModuleProperties properties;
    private final Environment environment;

    @PostConstruct
    public void validate() {
        boolean prod = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean defaultSecret = DEV_SECRET.equals(properties.getVisitorTokenSecret())
            || properties.getVisitorTokenSecret() == null
            || properties.getVisitorTokenSecret().isBlank();
        if (defaultSecret) {
            String message = "agent.customer-service.visitor-token-secret 仍为开发默认值,生产环境必须覆盖";
            if (prod) {
                throw new IllegalStateException("[CS] " + message);
            }
            log.warn("[CS] {} (dev 环境放行)", message);
        }
        boolean defaultChannelKey = "dev-only-cs-secret-key-change-me"
            .equals(properties.getChannelSecretKey());
        if (defaultChannelKey) {
            String message = "agent.customer-service.channel-secret-key 仍为开发默认值,生产环境必须覆盖";
            if (prod) {
                throw new IllegalStateException("[CS] " + message);
            }
            log.warn("[CS] {} (dev 环境放行)", message);
        }
    }
}
