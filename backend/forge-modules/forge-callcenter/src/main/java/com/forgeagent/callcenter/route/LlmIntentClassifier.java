package com.forgeagent.callcenter.route;

import com.forgeagent.callcenter.engine.ivr.IntentionClassifier;

import java.util.List;

/**
 * 语义意图引擎 SPI(C5 双引擎的 LLM 侧)。
 *
 * <p>为什么不直接在规则引擎里调模型:规则表是「动作的唯一来源」,
 * LLM 只负责判断「这段话是不是某个已声明的意图」,不负责决定转哪个队列——
 * 这样模型换代、切供应商都不影响业务动作。</p>
 *
 * <p>C5 只交付接口与装配条件:{@code agent.callcenter.route-llm-enabled=true}
 * 且容器内有实现时才调用。无实现一律降级到正则库并 WARN 一次,
 * 不静默假装分类成功(与 C2-real / C1-real 同一处置口径)。</p>
 */
public interface LlmIntentClassifier {

    /** 是否可用(API key、熔断、开关任一不满足即 false);false 时调用方不得调用 classify。 */
    boolean available();

    /** 语义分类;无结论返回 null。意图码必须能在规则表中找到,否则调用方会丢弃。 */
    IntentionClassifier.Result classify(Long workspaceId, String text, List<String> recentTexts);
}
