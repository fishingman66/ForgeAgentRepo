package com.forgeagent.callcenter.engine.ivr;

import java.util.List;

/**
 * 意图分类 SPI(C3):intention 节点的分类引擎。
 *
 * <p>默认实现 {@link RegexIntentionClassifier} 包装客服域 CsIntentService 的
 * 正则规则库(语音/文本同库);LLM 兜底实现(C4 洞察管道双引擎)注册为
 * 高优先级 Bean 后自动接管,节点层零改动。</p>
 */
public interface IntentionClassifier {

    /** 分类结果:intentCode 为意图编码,confidence -1 表示未知;未命中返回 null。 */
    Result classify(Long workspaceId, String text, List<String> recentTexts);

    record Result(String intentCode, double confidence) {
    }
}
