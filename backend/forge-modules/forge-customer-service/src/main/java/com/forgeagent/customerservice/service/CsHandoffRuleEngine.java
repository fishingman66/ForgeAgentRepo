package com.forgeagent.customerservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.customerservice.domain.CsTurnSignals;

import java.util.ArrayList;
import java.util.List;

/**
 * B2:转人工规则引擎(docs/11-B2)——规则优先于模型(对标取舍①)的落地收口。
 *
 * <p>此前转人工判定散落在 send() 的 5 处 if 里,加一条规则要改主链路代码。
 * 引擎按规则表顺序求值,命中即转、模型只作加分。规则来源两级:
 * 渠道 {@code handoff_rules} JSON(显式配置,整体替代默认)为空时用内置默认
 * (关键词 + 情绪阈值连续 2 轮 + 24h 重复进线第 2 次;轮次上限默认关闭,需运营显式开启)。</p>
 *
 * <p>与既有流程的关系:敏感词 BLOCK 与知识零命中仍是流程内固有闸门(依赖检索结果,
 * 无法在入口求值),不进本引擎;本引擎收口的是入口可判定的一类——关键词、情绪阈值、
 * 重复进线、轮次上限。情绪连击阈值固定为 {@link #DEFAULT_SENTIMENT_THRESHOLD}
 * (与语音洞察 C4 同口径),由信号服务统一计算,渠道只配连击轮数。</p>
 */
public final class CsHandoffRuleEngine {

    /** 情绪负面阈值:score ≤ 阈值判定负面(与语音洞察 C4 一致)。 */
    public static final double DEFAULT_SENTIMENT_THRESHOLD = -0.35;
    /** 默认负面连续轮数。 */
    public static final int DEFAULT_SENTIMENT_STREAK = 2;
    /** 默认重复进线触发次数(24h 内第 2 次)。 */
    public static final int DEFAULT_REPEAT_LIMIT = 2;
    /** 默认轮次上限(默认规则里该规则关闭)。 */
    public static final int DEFAULT_TURN_LIMIT = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 规则求值结果:hit 为真时 reason 落工单 handoff_reason,便于归因。 */
    public record HandoffDecision(boolean hit, String ruleType, String reason) {
        public static HandoffDecision none() {
            return new HandoffDecision(false, null, null);
        }
    }

    /** 单条规则:enabled=false 跳过;各限值缺省用内置默认。 */
    record RuleSpec(String type, Boolean enabled, Integer streakLimit,
                    Integer repeatLimit, Integer turnLimit) {
    }

    private CsHandoffRuleEngine() {
    }

    /** 按规则表顺序求值;JSON 解析失败降级为内置默认(兜底可靠性优先)。 */
    public static HandoffDecision evaluate(String content, String channelRulesJson,
                                           String platformKeywords, CsTurnSignals signals) {
        for (RuleSpec rule : parse(channelRulesJson)) {
            if (!Boolean.TRUE.equals(rule.enabled())) {
                continue;
            }
            HandoffDecision decision = switch (rule.type()) {
                case "KEYWORD" -> CsHandoffKeywords.matches(content, platformKeywords)
                    ? hit("KEYWORD", "命中转人工关键词") : null;
                case "SENTIMENT" -> signals.sentimentStreak() >= streakLimit(rule)
                    ? hit("SENTIMENT", "访客负面情绪连续 " + signals.sentimentStreak() + " 轮") : null;
                case "REPEAT" -> signals.repeatTotal24h() >= repeatLimit(rule)
                    ? hit("REPEAT", "同访客 24h 内第 " + signals.repeatTotal24h() + " 次进线") : null;
                case "TURN_LIMIT" -> signals.turnCount() >= turnLimit(rule)
                    ? hit("TURN_LIMIT", "机器人已连续应答 " + signals.turnCount() + " 轮未解决") : null;
                default -> null;
            };
            if (decision != null) {
                return decision;
            }
        }
        return HandoffDecision.none();
    }

    private static HandoffDecision hit(String type, String reason) {
        return new HandoffDecision(true, type, reason);
    }

    private static int streakLimit(RuleSpec rule) {
        return rule.streakLimit() == null || rule.streakLimit() <= 0
            ? DEFAULT_SENTIMENT_STREAK : rule.streakLimit();
    }

    private static int repeatLimit(RuleSpec rule) {
        return rule.repeatLimit() == null || rule.repeatLimit() <= 0
            ? DEFAULT_REPEAT_LIMIT : rule.repeatLimit();
    }

    private static int turnLimit(RuleSpec rule) {
        return rule.turnLimit() == null || rule.turnLimit() <= 0
            ? DEFAULT_TURN_LIMIT : rule.turnLimit();
    }

    /**
     * 解析渠道规则 JSON;空/坏 JSON 用内置默认。
     * JSON 是完整配置(显式替代默认),未写出的类型视为不启用;
     * 未知类型跳过,保证向前兼容。
     */
    static List<RuleSpec> parse(String channelRulesJson) {
        if (channelRulesJson == null || channelRulesJson.isBlank()) {
            return defaults();
        }
        try {
            List<RuleSpec> rules = new ArrayList<>();
            List<?> raw = MAPPER.readValue(channelRulesJson, List.class);
            for (Object item : raw) {
                if (!(item instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object type = map.get("type");
                if (type == null || String.valueOf(type).isBlank()) {
                    continue;
                }
                rules.add(new RuleSpec(
                    String.valueOf(type),
                    boolOf(map.get("enabled")),
                    intOf(map.get("streakLimit")),
                    intOf(map.get("repeatLimit")),
                    intOf(map.get("turnLimit"))));
            }
            return rules.isEmpty() ? defaults() : rules;
        } catch (Exception e) {
            return defaults();
        }
    }

    /** 内置默认:关键词 + 情绪阈值 + 重复进线开启,轮次上限默认关。 */
    static List<RuleSpec> defaults() {
        return List.of(
            new RuleSpec("KEYWORD", true, null, null, null),
            new RuleSpec("SENTIMENT", true, DEFAULT_SENTIMENT_STREAK, null, null),
            new RuleSpec("REPEAT", true, null, DEFAULT_REPEAT_LIMIT, null),
            new RuleSpec("TURN_LIMIT", false, null, null, DEFAULT_TURN_LIMIT));
    }

    private static Boolean boolOf(Object value) {
        if (value == null) {
            return true;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer intOf(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
