package com.forgeagent.callcenter.route;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.engine.ivr.IntentionClassifier;
import com.forgeagent.callcenter.engine.ivr.RegexIntentionClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 双引擎意图分类(C5,方案 docs/07 §三 P0-3):规则引擎 → 语义引擎 → 正则库兜底。
 *
 * <p><b>裁决顺序</b>(尊重 priority,不是简单的「先规则后模型」):</p>
 * <ol>
 *   <li>规则引擎按 priority 返回「正则命中」+「优先级更高的 LLM_ONLY 候选」;</li>
 *   <li>有候选且语义引擎可用 → 先问模型;模型给出的意图码必须在候选里(动作仍取自规则表),
 *       否则丢弃并继续——模型不能凭空发明动作;</li>
 *   <li>无模型结论 → 用正则命中(confidence 1.0);</li>
 *   <li>都没结论 → 落到客服域正则意图库(confidence 0.8,与规则命中区分,便于口径追溯)。</li>
 * </ol>
 *
 * <p>标 {@code @Primary} 以接管 C3 留下的 SPI 注入位:IVR {@code intention} 节点与
 * C4 洞察管道都按类型取 Bean,装配零改动。</p>
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class HybridIntentionClassifier implements IntentionClassifier {

    /** 规则命中的置信度:确定性匹配,给满分。 */
    static final double RULE_CONFIDENCE = 1.0;
    /** 正则库兜底的置信度:关键词命中不等于语义确定,留出区分度。 */
    static final double FALLBACK_CONFIDENCE = 0.8;

    private final RouteRuleEngine engine;
    private final RegexIntentionClassifier fallback;
    private final ObjectProvider<LlmIntentClassifier> llmProvider;
    private final CallcenterProperties properties;

    private final AtomicBoolean llmWarned = new AtomicBoolean(false);

    /** 分流决策:意图码 + 动作(动作只来自规则表)+ 引擎标识,供直接应答模式执行与留痕。 */
    public record Decision(String intentCode, String action, String actionArg,
                           String engine, String ruleCode, double confidence) {
    }

    @Override
    public Result classify(Long workspaceId, String text, List<String> recentTexts) {
        Decision decision = route(workspaceId, text, recentTexts);
        return decision == null ? null : new Result(decision.intentCode(), decision.confidence());
    }

    /**
     * 带动作的分流决策(C5 直接应答模式需要知道转哪个队列 / 走哪种应答);
     * 无任何结论返回 null。
     */
    public Decision route(Long workspaceId, String text, List<String> recentTexts) {
        if (text == null || text.isBlank()) {
            return null;
        }
        RouteRuleEngine.Evaluation evaluation = engine.evaluate(workspaceId, text, recentTexts);

        if (evaluation.hasLlmCandidate()) {
            Optional<LlmIntentClassifier> llm = usableLlm();
            if (llm.isPresent()) {
                IntentionClassifier.Result result = safeClassify(llm.get(), workspaceId, text, recentTexts);
                Decision accepted = acceptLlm(result, evaluation.llmCandidates());
                if (accepted != null) {
                    return accepted;
                }
            }
        }

        RouteRuleEngine.RouteMatch hit = evaluation.regexHit();
        if (hit != null) {
            return new Decision(hit.intentCode(), hit.action(), hit.actionArg(),
                "RULE", hit.ruleCode(), RULE_CONFIDENCE);
        }

        IntentionClassifier.Result fallbackResult = fallback.classify(workspaceId, text, recentTexts);
        if (fallbackResult == null || fallbackResult.intentCode() == null
            || fallbackResult.intentCode().isBlank()) {
            return null;
        }
        // 兜底路径没有动作配置:只标记意图,不改路由(动作是规则表的职责)
        return new Decision(fallbackResult.intentCode(), "MARK", null,
            "FALLBACK_REGEX", null, FALLBACK_CONFIDENCE);
    }

    /**
     * 采纳模型结论的唯一条件:意图码命中某个候选规则(高优先级 LLM_ONLY 声明过)。
     * 动作与置信度取自「规则 + 模型」两侧——动作来自规则,置信度来自模型。
     */
    private Decision acceptLlm(IntentionClassifier.Result result,
                               List<RouteRuleEngine.LlmCandidate> candidates) {
        if (result == null || result.intentCode() == null) {
            return null;
        }
        for (RouteRuleEngine.LlmCandidate candidate : candidates) {
            if (candidate.intentCode().equals(result.intentCode())) {
                return new Decision(candidate.intentCode(), candidate.action(), candidate.actionArg(),
                    "LLM", candidate.ruleCode(), result.confidence());
            }
        }
        log.debug("[CC][Route] 模型给出未声明的意图码,丢弃 code={}", result.intentCode());
        return null;
    }

    private IntentionClassifier.Result safeClassify(LlmIntentClassifier llm, Long workspaceId,
                                                    String text, List<String> recentTexts) {
        try {
            return llm.classify(workspaceId, text, recentTexts);
        } catch (Exception e) {
            // 模型故障不影响通话:降级到规则/正则库
            log.warn("[CC][Route] 语义引擎调用失败,降级: {}", e.getMessage());
            return null;
        }
    }

    /** 语义引擎可用性:开关 + 实现存在 + 实现自报可用;不可用时只告警一次。 */
    private Optional<LlmIntentClassifier> usableLlm() {
        if (!Boolean.TRUE.equals(properties.getRouteLlmEnabled())) {
            return degraded("未开启(agent.callcenter.route-llm-enabled=false)");
        }
        LlmIntentClassifier llm = llmProvider.getIfAvailable();
        if (llm == null) {
            return degraded("无 LlmIntentClassifier 实现");
        }
        if (!llm.available()) {
            return degraded("实现自报不可用");
        }
        return Optional.of(llm);
    }

    private Optional<LlmIntentClassifier> degraded(String reason) {
        if (llmWarned.compareAndSet(false, true)) {
            log.warn("[CC][Route] LLM_ONLY 规则降级为规则+正则库: {}", reason);
        }
        return Optional.empty();
    }
}
