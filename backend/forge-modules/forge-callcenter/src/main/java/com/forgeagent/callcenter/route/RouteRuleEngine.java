package com.forgeagent.callcenter.route;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsRouteRule;
import com.forgeagent.callcenter.mapper.CsRouteRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 分流规则引擎(C5,方案 docs/07 §三 P0-3):双引擎里的<b>规则侧</b>,纯正则、可离线复现。
 *
 * <p><b>匹配语义</b>:按 {@code priority} 升序逐条评估,第一条命中的正则规则即结论(命中即止)。
 * {@code LLM_ONLY} 规则不参与正则评估,只作为「候选」上抛——由 {@link HybridIntentionClassifier}
 * 决定交给语义引擎还是降级跳过,这样引擎本身保持无状态、无外部依赖、可单测。</p>
 *
 * <p><b>三道防护</b>(C5 验收点):</p>
 * <ol>
 *   <li><b>量词防护</b>:输入文本先截断到 {@code route-rule-max-text-chars};命中片段长度超过
 *       规则的 {@code max_match_length} 视为过匹配丢弃——{@code .*} 一把吞掉整段会让判定恒真;</li>
 *   <li><b>词组白名单</b>:{@code phrase_whitelist} 非空时文本须<b>至少出现其中一个</b>词组,
 *       用于压制「退」误命中「退休」这类过度泛化;</li>
 *   <li><b>坏模式隔离</b>:正则编译失败或疑似灾难回溯({@code (.*)+} 一类嵌套量词)只跳过该条并 WARN 一次,
 *       不拖垮整张规则表。</li>
 * </ol>
 *
 * <p>规则变更经 60s TTL 生效(与 C2 词表、C4 渠道配置同一口径),不做实时推送——
 * 分流规则的调整是分钟级运营动作,不需要秒级一致性。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteRuleEngine {

    public static final String TYPE_REGEX = "REGEX";
    public static final String TYPE_LLM_ONLY = "LLM_ONLY";

    private static final int PATTERN_CACHE_MAX = 512;
    /** 疑似灾难回溯:分组内量词又被量词修饰,(.*)+ / (.+)* / (\d+)+ 一类。 */
    private static final Pattern CATASTROPHIC = Pattern.compile("\\([^()]*[*+][^()]*\\)[*+]");

    private final CallcenterProperties properties;
    private final ObjectProvider<CsRouteRuleMapper> mapperProvider;

    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Pattern>> patternCache = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private record CacheEntry(List<CsRouteRule> rules, long loadedAt) {
    }

    /** 正则规则命中结果。 */
    public record RouteMatch(String intentCode, String action, String actionArg,
                             String ruleCode, int priority, String matchedText) {
    }

    /** LLM_ONLY 规则摘要:交给上层决定能否调用语义引擎。 */
    public record LlmCandidate(String intentCode, String action, String actionArg,
                               String ruleCode, int priority) {
    }

    /** 一次评估的结论:正则命中(可为 null)+ 优先级更高的待 LLM 候选(可为空)。 */
    public record Evaluation(RouteMatch regexHit, List<LlmCandidate> llmCandidates) {
        public static Evaluation empty() {
            return new Evaluation(null, List.of());
        }

        public boolean hasLlmCandidate() {
            return !llmCandidates.isEmpty();
        }
    }

    /**
     * 评估一段文本。{@code recentTexts} 暂不参与正则匹配(规则是词语级),
     * 形参保留以便 LLM 侧沿用同一签名。
     */
    public Evaluation evaluate(Long workspaceId, String text, List<String> recentTexts) {
        if (text == null || text.isBlank()) {
            return Evaluation.empty();
        }
        String normalized = normalize(text);
        List<LlmCandidate> candidates = new ArrayList<>();
        for (CsRouteRule rule : rules(workspaceId)) {
            if (TYPE_LLM_ONLY.equalsIgnoreCase(rule.getMatchType())) {
                candidates.add(new LlmCandidate(rule.getIntentCode(), rule.getAction(),
                    rule.getActionArg(), rule.getRuleCode(), priorityOf(rule)));
                continue;
            }
            RouteMatch match = matchRegex(rule, normalized);
            if (match != null) {
                // 命中即止:优先级更高的 LLM 候选已收集,交给上层按序裁决
                return new Evaluation(match, List.copyOf(candidates));
            }
        }
        return new Evaluation(null, List.copyOf(candidates));
    }

    /** 单条正则规则匹配(含量词防护与词组白名单);不命中返回 null。 */
    RouteMatch matchRegex(CsRouteRule rule, String normalizedText) {
        Optional<Pattern> compiled = compiled(rule.getPattern());
        if (compiled.isEmpty()) {
            return null;
        }
        Matcher matcher = compiled.get().matcher(normalizedText);
        if (!matcher.find()) {
            return null;
        }
        String matched = matcher.group();
        int cap = rule.getMaxMatchLength() == null
            ? defaultCap() : Math.max(1, rule.getMaxMatchLength());
        if (matched.length() > cap) {
            log.debug("[CC][Route] 量词防护命中片段过长,丢弃 rule={} len={} cap={}",
                rule.getRuleCode(), matched.length(), cap);
            return null;
        }
        if (!whitelistSatisfied(rule.getPhraseWhitelist(), normalizedText)) {
            log.debug("[CC][Route] 词组白名单未满足 rule={} whitelist={}",
                rule.getRuleCode(), rule.getPhraseWhitelist());
            return null;
        }
        return new RouteMatch(rule.getIntentCode(), rule.getAction(), rule.getActionArg(),
            rule.getRuleCode(), priorityOf(rule), matched);
    }

    /** 启用规则(priority 升序,同优先级按 id);60s 缓存。 */
    public List<CsRouteRule> rules(Long workspaceId) {
        long workspace = workspaceId == null ? 1L : workspaceId;
        long now = System.currentTimeMillis();
        long ttl = properties.getRouteRuleCacheTtlMs() == null ? 60_000L : properties.getRouteRuleCacheTtlMs();
        CacheEntry cached = cache.get(workspace);
        if (cached != null && now - cached.loadedAt() < ttl) {
            return cached.rules();
        }
        List<CsRouteRule> loaded = load(workspace);
        cache.put(workspace, new CacheEntry(loaded, now));
        return loaded;
    }

    /** 规则变更后立即生效(管理端保存或测试调用)。 */
    public void invalidate() {
        cache.clear();
    }

    private List<CsRouteRule> load(long workspaceId) {
        CsRouteRuleMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            return List.of();
        }
        try {
            List<CsRouteRule> rows = mapper.selectList(new LambdaQueryWrapper<CsRouteRule>()
                .eq(CsRouteRule::getWorkspaceId, workspaceId)
                .eq(CsRouteRule::getEnabled, "0")
                .eq(CsRouteRule::getDelFlag, 0L)
                .orderByAsc(CsRouteRule::getPriority)
                .orderByAsc(CsRouteRule::getId));
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            // 启用态在 SQL 里已过滤,这里再过一遍:调用方(缓存/测试/mapper 实现差异)不该承担这个前提
            List<CsRouteRule> usable = new ArrayList<>(rows.size());
            for (CsRouteRule row : rows) {
                if (!"0".equals(row.getEnabled()) || (row.getDelFlag() != null && row.getDelFlag() != 0L)) {
                    continue;
                }
                usable.add(row);
            }
            return List.copyOf(usable);
        } catch (Exception e) {
            // 规则表不可用:分流退化为无规则(上层还有语义引擎兜底),不阻断通话
            log.warn("[CC][Route] 规则加载失败,本次按无规则处理 ws={}: {}", workspaceId, e.getMessage());
            return List.of();
        }
    }

    /** 编译并缓存;非法模式与疑似灾难回溯只跳过本条。 */
    Optional<Pattern> compiled(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return Optional.empty();
        }
        if (patternCache.size() > PATTERN_CACHE_MAX) {
            patternCache.clear();
        }
        return patternCache.computeIfAbsent(pattern, raw -> {
            if (CATASTROPHIC.matcher(raw).find() && warned.add("catastrophic:" + raw)) {
                log.warn("[CC][Route] 规则含嵌套量词,疑似灾难回溯,已跳过: {}", raw);
                return Optional.empty();
            }
            try {
                return Optional.of(Pattern.compile(raw));
            } catch (PatternSyntaxException e) {
                if (warned.add("syntax:" + raw)) {
                    log.warn("[CC][Route] 规则正则非法,已跳过: {} ({})", raw, e.getDescription());
                }
                return Optional.empty();
            }
        });
    }

    /**
     * 白名单:逗号分隔,文本<b>至少出现其中一个</b>才算满足;空白项忽略。
     *
     * <p>语义取「允许集」而不是「全部共现」:白名单的用途是把过宽的正则收窄到业务认可的表述,
     * 例如 pattern 只写 {@code 退} 时,白名单 {@code 退货,退款,退钱} 能挡掉「退休」「退订」,
     * 同时不会要求一句话把三个词都说完。</p>
     */
    static boolean whitelistSatisfied(String whitelist, String text) {
        if (whitelist == null || whitelist.isBlank()) {
            return true;
        }
        for (String phrase : whitelist.split("[,，]")) {
            String trimmed = phrase.trim();
            if (!trimmed.isEmpty() && text.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化:折叠空白 + 截断(量词防护的第一道),避免超长分片拖慢正则。 */
    String normalize(String text) {
        String collapsed = text.replaceAll("\\s+", " ").trim();
        int cap = properties.getRouteRuleMaxTextChars() == null
            ? 200 : Math.max(1, properties.getRouteRuleMaxTextChars());
        return collapsed.length() <= cap ? collapsed : collapsed.substring(0, cap);
    }

    private int defaultCap() {
        return properties.getRouteRuleDefaultMaxMatchLength() == null
            ? 40 : Math.max(1, properties.getRouteRuleDefaultMaxMatchLength());
    }

    private static int priorityOf(CsRouteRule rule) {
        return rule.getPriority() == null ? 100 : rule.getPriority();
    }
}
