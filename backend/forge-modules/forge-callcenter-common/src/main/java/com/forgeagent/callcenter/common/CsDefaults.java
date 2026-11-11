package com.forgeagent.callcenter.common;

/**
 * 共享默认值与配置键名常量(V0.5,配置粒度取推荐档)。
 *
 * <p>只放 {@code public static final} 常量,不含 Spring 注解;
 * 配置 Bean 仍留在各自模块,prefix 不变化。业务配置类在 V1 起逐步引用这里的常量。</p>
 *
 * <p>扩展方向:新增可配置项 = 键名字符串先落本类(契约),默认值跟随业务域配置类;
 * 真实 AICC 的 endpoint / tenant-id / auth-mode 等键名在 V0-real 接入时补充。</p>
 */
public final class CsDefaults {

    /** 两个业务域的配置 prefix,保持与已交付配置一致。 */
    public static final String PREFIX_CUSTOMER_SERVICE = "agent.customer-service";
    public static final String PREFIX_CALLCENTER = "agent.callcenter";

    /** 与 CsModuleProperties 现有默认值一致。 */
    public static final int VISITOR_TOKEN_TTL_SECONDS = 1800;
    public static final int SESSION_IDLE_MINUTES = 30;
    public static final int CHANNEL_RATE_LIMIT_PER_MINUTE = 60;
    public static final int MAX_MESSAGE_CHARS = 4000;
    public static final int RETENTION_DAYS = 180;
    public static final int CHANNEL_CALLBACK_TOLERANCE_SECONDS = 300;
    public static final long SLA_FIRST_RESPONSE_MS = 60000L;
    public static final boolean PII_MASK_ENABLED = true;
    public static final boolean STREAM_ENABLED = true;

    /** 共享配置键名契约。 */
    public static final String KEY_TRANSPORT = "transport";
    public static final String KEY_RETENTION_DAYS = "retention-days";
    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_MOCK_ENABLED = "mock.enabled";
    public static final String KEY_RECORDING_RETENTION_DAYS = "recording-retention-days";
    public static final String KEY_AUDIO_EGRESS_ENABLED = "audio-egress-enabled";
    public static final String KEY_MAX_SEATS = "max-seats";
    public static final String KEY_MAX_CONCURRENT_CALLS = "max-concurrent-calls";
    public static final String KEY_BUDGET_MONTHLY_MINUTES = "budget-monthly-minutes";
    public static final String KEY_NUMBER_PROVIDER = "number-provider";
    public static final String KEY_TRUNK_PROVIDER = "trunk-provider";

    private CsDefaults() {
    }
}
