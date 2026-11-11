package com.forgeagent.agent.service.runner;

import com.forgeagent.rag.service.document.TokenEstimator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * L1 工具输出压缩装饰器(P3 / M1)。
 *
 * <p>包装 Toolkit 里注册的工具:纯文本且状态为 SUCCESS 的结果超过 token 阈值时,
 * 把原文交给 {@link RunWorkingMemory} 暂存,返回「指纹 + 开头片段 + 取回指引」的短文本。
 * 非文本结果(图片等)、错误结果、短结果、Redis 不可用都原样透传。</p>
 *
 * <p>只做装饰器,不依赖内核 Hook 事件语义;{@code AgentTool} 的默认元数据全部委托给被包装工具。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
public record WorkingMemoryGuard(AgentTool delegate, RunWorkingMemory workingMemory, String runId,
                                 int thresholdTokens, int previewChars) implements AgentTool {

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public boolean isReturnDirect() {
        return delegate.isReturnDirect();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param).map(this::compress);
    }

    private ToolResultBlock compress(ToolResultBlock block) {
        // 内核 ToolResultBlock.text() 的默认状态是 RUNNING(并非 SUCCESS),
        // SDK 会在随后切换为 SUCCESS;这里把 RUNNING/SUCCESS 都视为可压缩的正常结果
        if (block == null || workingMemory == null || !compressible(block.getState())) {
            return block;
        }
        List<ContentBlock> output = block.getOutput();
        if (output == null || output.isEmpty()) {
            return block;
        }
        StringBuilder full = new StringBuilder();
        for (ContentBlock part : output) {
            if (!(part instanceof TextBlock text)) {
                return block;
            }
            if (full.length() > 0) {
                full.append('\n');
            }
            full.append(text.getText());
        }
        String raw = full.toString();
        int cost = TokenEstimator.estimate(raw);
        if (cost <= thresholdTokens) {
            return block;
        }
        String fingerprint = workingMemory.store(runId, delegate.getName(), raw);
        if (fingerprint == null) {
            return block;
        }
        int preview = Math.max(0, previewChars);
        String head = raw.length() > preview ? raw.substring(0, preview) + "…" : raw;
        String placeholder = "[工作记忆] 工具 [" + delegate.getName() + "] 返回内容较长(约 "
            + cost + " tokens),完整原文已存入本轮工作记忆。\n"
            + "指纹: " + fingerprint + "\n"
            + "需要完整内容时调用 recall_working_memory(fingerprint=\"" + fingerprint
            + "\", offset=0, limit=4000),可增大 offset 分段读取。\n"
            + "原文开头:\n" + head;
        ToolResultBlock.Builder builder = ToolResultBlock.builder()
            .id(block.getId())
            .name(block.getName())
            .output(TextBlock.builder().text(placeholder).build())
            // 保留原状态,避免把内核后续依赖的 RUNNING/SUCCESS 语义改写掉
            .state(block.getState() == null ? ToolResultState.RUNNING : block.getState());
        if (block.getMetadata() != null) {
            builder.metadata(block.getMetadata());
        }
        log.debug("[WorkingMemory] 工具 {} 结果压缩: {} tokens -> 指纹 {}", delegate.getName(), cost, fingerprint);
        return builder.build();
    }

    /** 只有正常文本结果可压缩;错误/拒绝/中断结果原样交给模型处理 */
    private static boolean compressible(ToolResultState state) {
        return state == null || state == ToolResultState.RUNNING || state == ToolResultState.SUCCESS;
    }
}
