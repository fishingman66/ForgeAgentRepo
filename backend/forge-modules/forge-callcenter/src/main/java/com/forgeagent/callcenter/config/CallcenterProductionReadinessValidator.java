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
        if (prod && ("AICC_MOCK".equalsIgnoreCase(provider) || "FS_MOCK".equalsIgnoreCase(provider))) {
            throw new IllegalStateException("[CC] agent.callcenter.provider=" + provider + " 在 prod 下禁止使用");
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

        // C1-real:provider=FS 时校验 ESL 业务配置与 starter 连接密码
        boolean realFs = "FS".equalsIgnoreCase(provider);
        CallcenterProperties.FreeswitchProperties fs = properties.getFreeswitch();
        if (realFs && (fs == null || fs.getChannelCode() == null || fs.getChannelCode().isBlank())) {
            String message = "agent.callcenter.freeswitch.channel-code 在 provider=FS 时必填(ESL 事件归属的语音渠道)";
            if (prod) {
                throw new IllegalStateException("[CC] " + message);
            }
            log.warn("[CC] {} (dev 环境放行)", message);
        }
        // ESL 默认密码 ClueCon 必须覆盖:只读 starter 配置键,不落明文、不记日志
        String eslPassword = environment.getProperty("link.thingscloud.freeswitch.esl.inbound.default-password");
        if (prod && realFs && "ClueCon".equals(eslPassword)) {
            throw new IllegalStateException("[CC] ESL 默认密码 ClueCon 未修改,prod 必须通过 FS_ESL_PASSWORD 覆盖");
        }
        // 开发剧本仅限联调:prod 开启会让机器人按固定脚本"假装识别"
        if (prod && Boolean.TRUE.equals(properties.getDevScript())) {
            throw new IllegalStateException("[CC] agent.callcenter.dev-script 在 prod 下必须为 false");
        }

        // ------- P0~P2 新增策略门禁(方案 docs/14 总验收标准第3条) -------
        // 告警统一走 warnOrFail:dev 只告警(本地开箱可用),prod 阻断。
        if (Boolean.TRUE.equals(properties.getEnterpriseEnabled())) {
            // 配额下限:0 会让"超配额拒绝"恒真,等于全量封禁
            require(prod, "agent.callcenter.max-seats / max-concurrent-calls / budget-monthly-minutes "
                + "在企业配额开启时必须 > 0",
                positive(properties.getMaxSeats()) && positive(properties.getMaxConcurrentCalls())
                    && positive(properties.getBudgetMonthlyMinutes()));
        }
        if (Boolean.TRUE.equals(properties.getOverflowEnabled())) {
            String queueExtensions = properties.getFreeswitch() == null ? null
                : properties.getFreeswitch().getQueueExtensions();
            require(prod, "溢出策略开启时 freeswitch.queue-extensions 若有值必须为 name:ext 格式(逗号分隔)",
                validQueueExtensions(queueExtensions));
        }
        if (Boolean.TRUE.equals(properties.getDidRouteEnabled())
            || Boolean.TRUE.equals(properties.getBlacklistEnabled())) {
            // FR 红线:资质前 noop 是合法默认;开启 db provider 必须有明确配置
            String np = properties.getNumberProvider() == null ? "noop" : properties.getNumberProvider();
            String tp = properties.getTrunkProvider() == null ? "noop" : properties.getTrunkProvider();
            require(prod, "DID 路由/黑名单开启且使用 db provider 时,必须确认 SIP 中继资质已到位(FR 红线)",
                !("db".equalsIgnoreCase(np) || "db".equalsIgnoreCase(tp)));
        }
    }

    /** dev 告警 / prod 阻断。 */
    private void require(boolean prod, String message, boolean ok) {
        if (ok) {
            return;
        }
        if (prod) {
            throw new IllegalStateException("[CC] " + message);
        }
        log.warn("[CC] {} (dev 环境放行)", message);
    }

    private static boolean positive(Integer v) {
        return v != null && v > 0;
    }

    /** queue-extensions 为空合法(队列名直接当分机号);有值则每段必须 name:ext。 */
    static boolean validQueueExtensions(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        for (String pair : raw.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2 || kv[0].trim().isEmpty() || kv[1].trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
