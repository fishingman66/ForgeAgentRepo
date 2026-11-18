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
     * 媒体流实现(C1-real):mock(可编程音频注入,无 FS 环境)/ fs(mod_audio_stream WebSocket)。
     * 与 provider 独立——provider 决定事件源,本项决定音频管道。
     */
    private String mediaStreamProvider = "mock";

    /**
     * P3 IVR 自动驱动开关:接通后自动跑 IVR 流程(route-code 指定)。
     * 关闭时接通只登记通道与媒体流,IVR 由外部触发(默认关,生产按需开)。
     */
    private Boolean ivrAutodrive = false;

    /** IVR 会话线程池大小:每路通话占一个线程跑阻塞式流程循环。 */
    private Integer ivrPoolSize = 4;

    /**
     * 开发剧本开关(dev-script):给 mock ASR 预置默认对话脚本 + TTS 自动步进,
     * 无 API key 也能真机拨测全链路(唯一假的是识别内容)。prod 由校验器拒绝开启。
     */
    private Boolean devScript = false;

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

    /** C5 分流规则缓存 TTL(毫秒):规则变更由管理端保存后靠 TTL 生效,避免每次分片查库。 */
    private Long routeRuleCacheTtlMs = 60_000L;

    /** C5 分片文本评估长度上限(字符):超长文本截断后再匹配,压制正则开销。 */
    private Integer routeRuleMaxTextChars = 200;

    /** C5 规则未声明 max_match_length 时的兜底上限(字符)。 */
    private Integer routeRuleDefaultMaxMatchLength = 40;

    /**
     * C5 LLM 引擎开关:true 且容器内存在 {@code LlmIntentClassifier} 实现时才真正调用;
     * 无实现一律降级到正则库并告警一次(不静默假装分类成功)。
     */
    private Boolean routeLlmEnabled = false;

    /** C1-real FreeSWITCH 真实接入(ESL 连接参数走 starter 的 link.thingscloud.freeswitch.esl.* 前缀)。 */
    private FreeswitchProperties freeswitch = new FreeswitchProperties();

    /**
     * FreeSWITCH 真实接入的业务侧配置。
     * ESL 连接(host/port/password/events)由 starter 自带配置绑定,
     * 本类只承载归一化与录音取回需要的参数。
     */
    @Data
    public static class FreeswitchProperties {
        /**
         * ESL 事件归属的语音渠道编码,对应 agent_cc_channel_config.channel_code,
         * 该渠道 media_provider 须为 FS。ESL 事件没有 URL 渠道段,由此配置兜底。
         */
        private String channelCode = "CC_FS";

        /** FS 录音落盘根目录,用于 fetchRecording 路径白名单校验(防目录穿越)。 */
        private String recordingDir;

        /**
         * ESL 命令下发地址(host:port),须与 starter 的 servers 第一项一致。
         * 仅用于 uuid_* 命令寻址,不参与事件订阅。
         */
        private String eslAddress = "127.0.0.1:8021";

        /** uuid_transfer 的 dialplan context(FreeSWITCH 默认 default)。 */
        private String dialplanContext = "default";

        /**
         * ACD 队列名 -> dialplan 号码映射(逗号分隔,如 after_sales:5000)。
         * mod_callcenter 的入场方式是 dialplan 中该号码执行 callcenter 应用,
         * 因此转人工要先转号码而非队列名;未配置的队列名按原值直接当号码转。
         */
        private String queueExtensions;

        /**
         * 媒体面 WebSocket 对外地址(FS 侧能访问到的地址),接通时拼成
         * {@code ws://<host>:<port>/cc/media/<callId>} 下发给 uuid_audio_stream。
         * 同机部署填 127.0.0.1;注意 FS 在云主机上时不能用容器内网名。
         */
        private String mediaWsBase = "ws://127.0.0.1:8080";

        /** 音频采样率:8k(电话口径,与 MediaStream 接口一致) / 16k(ASR 精度更高)。 */
        private String mediaSampleRate = "8k";

        /** IVR 自动驱动时加载的流程编码(对应 agent_cc_ivr_flow.route_code,种子为 demo_inbound)。 */
        private String ivrRouteCode = "demo_inbound";
    }
}
