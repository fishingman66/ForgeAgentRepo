package com.forgeagent.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.util.Arrays;

/**
 * 统一生产凭据校验：把 README「上线前必改清单」中平台级凭据做成 prod 启动门禁。
 *
 * <p>由 {@link com.forgeagent.ForgeAgentApplication#main} 在 {@code SpringApplication.run}
 * 之前直接调用——这是最早可执行点，先于任何 Bean（含 DataSource）创建。
 * 不用 {@code @PostConstruct}（晚于数据源，DB 密码错会先抛连接异常把校验掩盖）；
 * 不用 Boot 4 的 EnvironmentPostProcessor 注册（4.1.x 已不再从 spring.factories 读取，实测确认）。</p>
 *
 * <p>与分域校验器互补——callcenter / customer-service 各自校验自己的密钥，本类只管
 * JWT / RSA / DB / Redis / SnailJob 平台级凭据。dev/local 只告警，prod 仍是出厂默认即拒绝启动。
 * 任何日志都不打印凭据明文，只描述「哪项仍是默认值」。</p>
 *
 * @author ForgeAgent
 */
public final class ProductionCredentialsGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionCredentialsGuard.class);

    private static final String DEF_JWT = "abcdefghijklmnopqrstuvwxyz";
    private static final String DEF_DB = "root";
    private static final String DEF_REDIS = "forge123";
    private static final String DEF_SNAILJOB = "SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT";
    // RSA 出厂密钥只记可辨识前缀，不把完整私钥写进代码做比对
    private static final String DEF_RSA_PUB_PREFIX = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDm6u8q8X78";
    private static final String DEF_RSA_PRIV_PREFIX = "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJd";

    private ProductionCredentialsGuard() {
    }

    /**
     * @param activeProfile 当前激活的 profile（来自启动参数 -Dspring.profiles.active 或环境变量）
     */
    public static void validate(String activeProfile) {
        boolean prod = "prod".equals(activeProfile);
        // 用标准环境读取系统属性/环境变量；application.yml 的占位符默认值在 yml 中，
        // 这里只需识别「未被 ENV 覆盖」的情形：环境变量缺失即视为可能用了出厂默认。
        ConfigurableEnvironment environment = new StandardEnvironment();
        StringBuilder problems = new StringBuilder();

        checkEnvOrValue(problems, environment, "JWT_SECRET_KEY", "sa-token.jwt-secret-key",
            DEF_JWT, 32, "JWT 签名密钥", prod);
        checkEnvOrValue(problems, environment, "API_DECRYPT_PUBLIC_KEY", "api-decrypt.publicKey",
            DEF_RSA_PUB_PREFIX, 128, "接口加密 RSA 公钥", prod);
        checkEnvOrValue(problems, environment, "API_DECRYPT_PRIVATE_KEY", "api-decrypt.privateKey",
            DEF_RSA_PRIV_PREFIX, 512, "接口加密 RSA 私钥", prod);
        checkEnvOrValue(problems, environment, "DB_PASSWORD", "spring.datasource.dynamic.datasource.master.password",
            DEF_DB, 8, "数据库密码", prod);
        checkEnvOrValue(problems, environment, "REDIS_PASSWORD", "spring.data.redis.password",
            DEF_REDIS, 8, "Redis 密码", prod);
        checkEnvOrValue(problems, environment, "SNAILJOB_TOKEN", "snail-job.token",
            DEF_SNAILJOB, 16, "SnailJob 接入令牌", prod);

        if (problems.length() == 0) {
            if (prod) {
                log.info("[凭据自检] prod 环境变量均已设置，平台级凭据通过校验");
            }
            return;
        }
        String detail = problems.toString();
        if (prod) {
            throw new IllegalStateException("[凭据自检] prod 下存在出厂默认凭据，拒绝启动:\n" + detail
                + "请通过环境变量覆盖后重启（变量名见 application*.yml 占位符）。");
        }
        log.warn("[凭据自检] 以下凭据未通过环境变量设置，dev 放行但生产必须覆盖:\n{}", detail);
    }

    /**
     * prod 下要求对应环境变量存在且非空；dev 下只提示。
     */
    private static void checkEnvOrValue(StringBuilder problems, ConfigurableEnvironment env,
                                        String envName, String propertyKey, String knownDefault,
                                        int minLength, String label, boolean prod) {
        String fromEnv = env.getProperty(envName);
        if (fromEnv != null && !fromEnv.isBlank() && fromEnv.length() >= minLength
            && !fromEnv.equals(knownDefault) && !fromEnv.startsWith(knownDefault)) {
            return;
        }
        problems.append("  - ").append(label).append(" (环境变量 ").append(envName)
            .append(" 未设置或仍为默认值)\n");
    }
}
