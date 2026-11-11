package com.forgeagent.callcenter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音与呼叫中心模块配置(V1,prefix {@code agent.callcenter})。
 *
 * <p>与文本域 {@code agent.customer-service} 互不重名、互不干扰;
 * 共享默认值与键名契约见公共层 {@code CsDefaults}。共享密钥走环境变量,
 * 生产默认值由 {@link CallcenterProductionReadinessValidator} 拒绝启动。</p>
 */
@Data
@ConfigurationProperties("agent.callcenter")
public class CallcenterProperties {

    /** 模块总开关,默认关;关闭时语音端点与 Bean 均不装配,主应用行为零变化。 */
    private Boolean enabled = false;

    /** 供应商标识:AICC_MOCK(dev/test) / AICC;业务层只认归一化事件。 */
    private String provider = "AICC_MOCK";

    /** Mock 开关,仅 dev/test;prod profile 下为 true 时拒绝启动。 */
    private Boolean mockEnabled = true;

    /** 回调共享密钥(≥16 字符),Mock 契约 dev 默认值,生产必须覆盖。 */
    private String callbackSecret = "dev-only-aicc-mock-secret-2026";

    /** 回调时间窗(秒),超出直接拒绝,防重放。 */
    private Integer callbackToleranceSeconds = 300;

    /** 录音留存天数(D6 可配),到期由 CallRetentionTask 清理,独立于文本 retentionDays。 */
    private Integer recordingRetentionDays = 180;

    /** 录音存储实现:oss(sys_oss_config,生产) / local-fs(本地文件系统,开发无 OSS 时)。 */
    private String recordingStore = "oss";

    /** local-fs 模式的存储目录。 */
    private String recordingLocalDir = System.getProperty("user.home") + "/.forgeagent/cc-recordings";

    /** 音频出域开关(D6 可配):false 时录音副本只进本地 OSS,不转发任何第三方。 */
    private Boolean audioEgressEnabled = false;

    /** 录音签名访问 URL 有效期(秒)。 */
    private Integer recordingPresignTtlSeconds = 300;

    /** 转写文本推控制面弹屏开关。 */
    private Boolean transcriptPushEnabled = true;

    /** D8 规模口径:坐席上限。 */
    private Integer maxSeats = 30;

    /** D8 规模口径:并发通话上限。 */
    private Integer maxConcurrentCalls = 30;

    /** D8 规模口径:月分钟预算,超 100% 拒绝新呼入(V2 门禁)。 */
    private Integer budgetMonthlyMinutes = 400000;

    /** 号码扩展接口实现:noop(暂无号码)。 */
    private String numberProvider = "noop";

    /** 中继扩展接口实现:noop(暂无 SIP 中继)。 */
    private String trunkProvider = "noop";
}
