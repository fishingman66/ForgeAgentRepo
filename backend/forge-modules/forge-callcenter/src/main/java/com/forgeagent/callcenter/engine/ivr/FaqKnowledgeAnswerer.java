package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.service.CsKnowledgeAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库问答默认实现(C3):包装客服域 FAQ 库(确定性答案,问题精确/包含匹配)。
 */
@Component
@RequiredArgsConstructor
public class FaqKnowledgeAnswerer implements KnowledgeAnswerer {

    private final CsKnowledgeAnswerService knowledgeAnswerService;

    @Override
    public String answer(Long workspaceId, String question) {
        CsKnowledgeAnswer hit = knowledgeAnswerService.match(workspaceId, question);
        return hit == null ? null : hit.getAnswer();
    }
}
