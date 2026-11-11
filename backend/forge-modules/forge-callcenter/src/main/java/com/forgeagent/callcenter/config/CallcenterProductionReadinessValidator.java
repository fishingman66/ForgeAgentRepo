package com.forgeagent.callcenter.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 语音模块生产就绪校验(V1):prod 下 Mock 开关开启或共享密钥仍为开发默认值时拒绝启动;
 * dev 只告警不阻断,保证本地开发开箱可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallcenterProductionReadinessValidator {

    private static final String DEV_SECRET = "dev-only-aicc-mock-secret-2026";

    private final CallcenterProperties properties;
    private final Environment environment;

    @PostConstruct
    public void validate() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prod && Boolean.TRUE.equals(properties.getMockEnabled())) {
            throw new IllegalStateException("[CC] agent.callcenter.mock-enabled 在 prod 下必须为 false");
        }
        String provider = properties.getProvider() == null ? "" : properties.getProvider();
        if (prod && "AICC_MOCK".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("[CC] agent.callcenter.provider=AICC_MOCK 在 prod 下禁止使用");
        }
        String secret = properties.getCallbackSecret();
        boolean defaultSecret = secret == null || secret.isBlank()
            || secret.length() < 16 || DEV_SECRET.equals(secret);
        if (defaultSecret) {
            String message = "agent.callcenter.callback-secret 缺失、长度不足 16 或仍为开发默认值,生产环境必须覆盖";
            if (prod) {
                throw new IllegalStateException("[CC] " + message);
            }
            log.warn("[CC] {} (dev 环境放行)", message);
        }
    }
}
