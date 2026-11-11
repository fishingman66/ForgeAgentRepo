package com.forgeagent.customerservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能客服模块配置(S0)。
 *
 * <p>开关与通道参数与全局 {@code message.*} 完全隔离,避免影响登录用户的通知推送。</p>
 */
@Data
@ConfigurationProperties("agent.customer-service")
public class CsModuleProperties {

    /** 模块总开关;关闭时客服 Bean 与端点均不装配,主应用行为零变化。 */
    private Boolean enabled = true;

    /** 实时通道:sse(默认,文本期) / websocket(语音期,C5)。 */
    private String transport = "sse";

    /** 访客令牌有效期(秒)。 */
    private Integer visitorTokenTtlSeconds = 1800;

    /** 会话空闲多久自动结束(分钟)。 */
    private Integer sessionIdleMinutes = 30;

    /** 渠道默认每分钟请求上限。 */
    private Integer channelRateLimitPerMinute = 60;

    /** 单个访客消息体最大字符数。 */
    private Integer maxMessageChars = 4000;

    /** 客服消息与会话留存天数,超期由 CsComplianceService 清理。 */
    private Integer retentionDays = 180;

    /** PII 脱敏开关;关闭时仅审计不脱敏(不建议生产关闭)。 */
    private Boolean piiMaskEnabled = true;

    /** 逐字流式 SSE 开关。 */
    private Boolean streamEnabled = true;

    /** 渠道回调验签密钥的加密主密钥;生产环境必须覆盖。 */
    private String channelSecretKey = "dev-only-cs-secret-key-change-me";

    /** 渠道回调时间窗(秒),超出直接拒绝,防重放。 */
    private Integer channelCallbackToleranceSeconds = 300;

    /** 坐席首响 SLA 阈值(毫秒),运营看板据此算达标率。 */
    private Long slaFirstResponseMs = 60000L;

    /** 转人工关键词(逗号分隔);渠道级 handoff_keywords 优先于本配置。 */
    private String handoffKeywords = "转人工,人工客服,人工服务,真人客服,找人工,转客服";

    /** SLA 扫描开关;要关闭扫描请用本开关,不要把 sla-scan-ms 配成 0(定时任务周期必须 >0)。 */
    private Boolean slaEnabled = true;

    /** SLA 扫描间隔(毫秒),必须 >0;扫描关闭用 sla-enabled。 */
    private Long slaScanMs = 60000L;

    /** WAITING_AGENT 超过多少分钟视为超时告警。 */
    private Integer slaWaitingAgentMinutes = 10;

    /** PROCESSING 超过多少分钟视为超时告警。 */
    private Integer slaProcessingMinutes = 120;

    /** WAITING_CONFIRM 超过多少小时自动确认(客户不点时不让工单永远挂着)。 */
    private Integer slaConfirmHours = 24;

    /** RESOLVED 超过多少小时自动关闭。 */
    private Integer slaCloseHours = 72;

    /** 是否开启自动确认/自动关闭;关闭时只告警。 */
    private Boolean slaAutoAdvance = true;

    /** 敏感词拦截开关(入站与出站),词库见 agent_cs_sensitive_word。 */
    private Boolean sensitiveWordEnabled = true;

    /** 语义缓存开关(默认关):开启后只缓存白名单意图的通用问答。 */
    private Boolean semanticCacheEnabled = false;

    /** 可缓存的意图编码(逗号分隔);意图为空或不在白名单一律不缓存。 */
    private String semanticCacheIntents = "RETURN_REFUND,INVOICE";

    /** 近似命中阈值(0~1),越高越保守;0.95 只容忍一两个字的差异。 */
    private Double semanticCacheThreshold = 0.95;

    /** 槽位填充开关(默认关):开启后意图命中同名表单时自动进入收集流程。 */
    private Boolean slotFillingEnabled = false;

    /** 配额开关:按「空间当日 token 用量」拦截,超限可拒绝或仅告警。 */
    private Boolean quotaEnabled = false;

    /** 空间当日 token 上限(0=不限);口径是该空间所有模型调用,不只客服会话。 */
    private Long quotaDailyTokenLimit = 0L;

    /** 超额处置:WARN 只记日志 / BLOCK 拒绝并转人工(转人工不再消耗模型)。 */
    private String quotaAction = "WARN";

    /** 参考单价(元/千 token);0 表示不计算金额,只报 token,不伪造 0 成本。 */
    private Double costPer1kTokens = 0.0;

    /** 金额币种。 */
    private String costCurrency = "CNY";

    /**
     * 多 Agent 意图路由开关(P3):开启后新会话按首条消息意图选择专家 Agent。
     *
     * <p>只在会话首条消息生效——客服会话与 agent_session 一对一绑定，
     * 每轮换 Agent 等于每轮换会话、上下文全断。</p>
     */
    private Boolean agentRoutingEnabled = true;

    /**
     * 图谱辅助检索(M3 后续):把访客文本命中的图谱实体扩展成 1 跳邻接实体词,
     * 拼进知识检索的 query 提升召回。只扩检索词,不动 FAQ 确定性匹配与语义缓存键。
     */
    private Boolean kgExpandEnabled = false;

    /** 图谱扩展词上限;邻接实体再多也只取前 N 个,防止 query 被撑爆。 */
    private Integer kgExpandMaxTerms = 3;

    /**
     * 槽位表单:key 与意图编码同名即自动触发。
     *
     * <p>默认两张:退换退款要订单号 + 原因,发票要订单号 + 抬头。pattern 为空的槽位
     * 视为自由文本(上一轮追问后整句作为值),带 pattern 的从任意一轮文本里抽取。</p>
     */
    private Map<String, List<Slot>> slotForms = defaultSlotForms();

    /** 单个槽位定义。 */
    @Data
    public static class Slot {
        private String name;
        private String label;
        private String pattern;
        private Boolean required = true;
    }

    private static Map<String, List<Slot>> defaultSlotForms() {
        Map<String, List<Slot>> forms = new LinkedHashMap<>();
        forms.put("RETURN_REFUND", List.of(
            slot("orderNo", "订单号", "\\d{8,}"),
            slot("reason", "退货原因", null)));
        forms.put("INVOICE", List.of(
            slot("orderNo", "订单号", "\\d{8,}"),
            slot("title", "发票抬头", null)));
        return forms;
    }

    private static Slot slot(String name, String label, String pattern) {
        Slot slot = new Slot();
        slot.setName(name);
        slot.setLabel(label);
        slot.setPattern(pattern);
        slot.setRequired(true);
        return slot;
    }

    /** 访客令牌 HMAC 密钥;生产环境必须覆盖(由 CsProductionReadinessValidator 校验)。 */
    private String visitorTokenSecret = "dev-only-change-me-before-production";

    /** 坐席辅助(A1/A2):文本与语音两条触发链的公共口径。 */
    private Assist assist = new Assist();

    /**
     * 坐席辅助参数(方案 4.4 / 第七章 A2)。
     *
     * <p>节流按通话维度而非会话维度:一通电话内 ASR 定稿分片密集,
     * 按通话节流才能既覆盖客户连续追问、又不误伤同一会话的多通来电。</p>
     */
    @Data
    public static class Assist {

        /** 同一通话两次推荐的最小间隔(秒);ASR 中间结果不参与计数。 */
        private Integer throttleSeconds = 10;

        /** 语音推荐的最低 ASR 置信度(0~1),低于此值只入库不推荐。 */
        private Double minAsrConfidence = 0.6;

        /** 零采纳回流定时任务开关;cron 由 agent.customer-service.assist.recycle-cron 单独配置。 */
        private Boolean recycleEnabled = true;

        /** 回流只处理多少小时之前的未采纳推荐。 */
        private Integer recycleHours = 24;

        /** 单次回流的扫描上限。 */
        private Integer recycleLimit = 50;
    }
}
