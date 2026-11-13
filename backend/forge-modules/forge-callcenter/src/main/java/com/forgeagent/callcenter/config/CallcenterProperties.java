package com.forgeagent.callcenter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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

    /** C2 级联链路 ASR 引擎标识:mock(C1~C2 开发)/ dashscope(C2-real,需 API key)。 */
    private String asrEngine = "mock";

    /** C2 级联链路 TTS 引擎标识:mock / dashscope。 */
    private String ttsEngine = "mock";

    /**
     * 不允许打断词组兜底表(C2):DB 表 agent_cc_voice_no_interrupt 之外的开发兜底;
     * 精确匹配 partial 文本(trim 后全等),命中即忽略本次打断。
     */
    private List<String> noInterruptWords = List.of("嗯", "哦", "好的", "嗯嗯", "啊");

    /** C4 洞察管道总开关:关闭时挂点直接返回,不建线程池、不写洞察表。 */
    private Boolean insightEnabled = true;

    /** C4 洞察线程池常驻线程数(旁路线程,与通话主链路隔离)。 */
    private Integer insightPoolSize = 2;

    /** C4 洞察线程池有界队列容量;满时拒绝并 WARN,绝不阻塞回调线程。 */
    private Integer insightQueueCapacity = 512;

    /**
     * C4 单次洞察硬超时(毫秒):看门狗到点 interrupt 工作线程,
     * 超时按失败处理(空转游标 + WARN),不让毒分片卡住整条管道。
     */
    private Long insightTimeoutMs = 3000L;

    /** C4 情绪按轮次节流:同一通话两次情绪评估的最小间隔(毫秒)。 */
    private Long sentimentMinIntervalMs = 3000L;

    /** C4 负面情绪阈值:score ≤ 阈值判定 NEGATIVE(score 取值 [-1,1])。 */
    private Double negativeScoreThreshold = -0.35;

    /** C4 负面自动转接开关:默认关(方案 v2 解除边界后的可选项)。 */
    private Boolean autoTransferOnNegative = false;

    /** C4 负面自动转接目标队列(mod_callcenter queue);为空视为未配置,开关打开也不转。 */
    private String autoTransferQueue;

    /** C4 信息提取兜底字段:渠道 voice_config_json 未声明 extract_keys 时使用。 */
    private List<String> extractDefaultKeys = List.of("orderNo", "amount");
}
