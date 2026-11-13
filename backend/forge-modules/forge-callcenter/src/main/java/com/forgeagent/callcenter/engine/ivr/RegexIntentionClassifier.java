package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.customerservice.service.CsIntentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 意图分类默认实现(C3):包装客服域 {@link CsIntentService} 正则规则库,
 * 语音/文本同库(channel 维度暂按 workspaceId 区分);LLM 兜底由 C4 双引擎接管。
 */
@Component
@RequiredArgsConstructor
public class RegexIntentionClassifier implements IntentionClassifier {

    private final CsIntentService intentService;

    @Override
    public Result classify(Long workspaceId, String text, List<String> recentTexts) {
        CsIntentService.Match match = intentService.classify(workspaceId, text, recentTexts);
        if (match == null) {
            return null;
        }
        return new Result(match.code(), 1.0);
    }

    /** 最近终稿历史辅助:驱动器维护的滚动窗口(最多 3 轮)。 */
    public static List<String> recentOf(ArrayDeque<String> history) {
        List<String> all = new java.util.ArrayList<>(history);
        return all;
    }
}
