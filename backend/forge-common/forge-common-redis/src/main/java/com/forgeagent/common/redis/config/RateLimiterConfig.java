package com.forgeagent.common.redis.config;

import com.forgeagent.common.redis.aspectj.RateLimiterAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConfiguration;

/**
 * 限流功能配置。
 *
 * @author guangxin; 改造: 智造 ForgeAgent 2026-11
 * @date 2023/1/18
 */
@AutoConfiguration(after = RedisConfiguration.class)
public class RateLimiterConfig {

    /**
     * 创建限流切面。
     *
     * @return 限流切面
     */
    @Bean
    public RateLimiterAspect rateLimiterAspect() {
        return new RateLimiterAspect();
    }

}
