package com.forgeagent.agent.service.support;

import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.service.document.TokenEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L0 上下文窗口预算分配器(P3 / M1)。
 *
 * <p>把此前分散写死在各处的上下文预算(记忆 1500 字符 / RAG 2000 tokens / 附件 3000 tokens /
 * 历史 20 条)统一收编为「一个总盘 + 固定优先级」:静态 basePrompt 与用户消息先扣除,
 * 再按 摘要 → 长期记忆 → 问数语义 → 知识检索 → 附件 → 历史 的顺序分配剩余预算。
 * 高优先级块没用满的配额自动让渡给后面的块(例如 RAG 零命中时历史可以拿到更多额度)。</p>
 *
 * <p>动态块按 KVCache 友好顺序排列:静态系统提示词在前,检索/附件等轮级变化内容
 * 交给 AgentScopeRunner 追加在末尾(现有 buildAgent 行为不变)。</p>
 *
 * @author ForgeAgent
 */
@Component
@RequiredArgsConstructor
public class ContextBudgetAllocator {

    /** 单块裁剪后至少保留的 token 数,低于该值直接丢弃整块 */
    private static final int MIN_BLOCK_TOKENS = 64;
    /** 角色标记 / 小节标题等结构开销 */
    private static final int OVERHEAD_TOKENS = 64;
    private static final String TRUNCATE_MARK = "\n…[已按上下文预算裁剪]";

    private final AgentChatProperties chatProperties;

    /**
     * 分配输入
     *
     * @param windowTokens   本轮上下文窗口预算(tokens)
     * @param outputReserve  为模型输出预留的 tokens
     * @param basePrompt     静态系统提示词(模板编译结果 + 澄清提示)
     * @param summary        L2 会话摘要块(已带小节标题)
     * @param memory         L3 长期记忆块(已带小节标题)
     * @param sqlSchema      问数语义上下文块
     * @param rag            知识库检索上下文块
     * @param attachment     会话附件上下文块
     * @param userContent    本轮用户消息(送模文本)
     * @param timezonePrompt 恒定时区说明(由内核追加在末尾)
     */
    public record Spec(int windowTokens, int outputReserve, String basePrompt, String summary,
                       String memory, String sqlSchema, String rag, String attachment,
                       String userContent, String timezonePrompt) {
    }

    /**
     * 分配结果
     *
     * @param systemPrompt     可直接送内核的系统提示词(静态块 + 已裁剪的高优先级块)
     * @param retrievedContext 可直接送内核的检索上下文(附件 + RAG,已裁剪)
     * @param historyBudget    会话历史可用 tokens(至少 historyMinTokens)
     * @param usedTokens       已装配内容占用的 tokens(不含历史)
     * @param windowTokens     实际生效的窗口预算
     * @param blockTokens      各块实际占用 tokens(供 Trace 下钻)
     * @param trimmed          被裁剪或丢弃的块名(供 Trace 下钻)
     */
    public record Allocation(String systemPrompt, String retrievedContext, int historyBudget,
                             int usedTokens, int windowTokens, Map<String, Integer> blockTokens,
                             List<String> trimmed) {
    }

    /** 配置中的窗口预算(容错兜底) */
    public int defaultWindowTokens() {
        Integer v = chatProperties.getContextWindowTokens();
        return v == null || v <= 0 ? 16000 : v;
    }

    /** 配置中的输出预留(容错兜底) */
    public int defaultOutputReserve() {
        Integer v = chatProperties.getContextOutputReserve();
        return v == null || v < 0 ? 2000 : v;
    }

    public Allocation allocate(Spec spec) {
        int window = spec.windowTokens() > 0 ? spec.windowTokens() : defaultWindowTokens();
        int reserve = Math.max(0, Math.min(spec.outputReserve(), window / 2));
        int fixed = estimate(spec.basePrompt()) + estimate(spec.userContent())
            + estimate(spec.timezonePrompt()) + OVERHEAD_TOKENS;
        int historyMin = historyMinTokens();
        // 动态块 + 历史共享的额度;历史至少保留 historyMin,避免长检索把历史全部挤掉
        int dynamicCap = Math.max(0, window - reserve - fixed - historyMin);
        int[] remaining = {dynamicCap};
        Map<String, Integer> used = new LinkedHashMap<>();
        List<String> trimmed = new ArrayList<>();

        String summary = fit("summary", spec.summary(), remaining, used, trimmed);
        String memory = fit("memory", spec.memory(), remaining, used, trimmed);
        String sqlSchema = fit("sqlSchema", spec.sqlSchema(), remaining, used, trimmed);
        String rag = fit("rag", spec.rag(), remaining, used, trimmed);
        String attachment = fit("attachment", spec.attachment(), remaining, used, trimmed);

        String systemPrompt = joinSections(spec.basePrompt(), summary, memory, sqlSchema);
        // 与改造前保持一致:附件块在前、知识检索块在后
        String retrievedContext = joinSections(attachment, rag);
        int historyBudget = historyMin + remaining[0];
        int usedTokens = fixed + used.values().stream().mapToInt(Integer::intValue).sum();
        return new Allocation(systemPrompt, retrievedContext, historyBudget, usedTokens,
            window, used, trimmed);
    }

    private int historyMinTokens() {
        Integer v = chatProperties.getHistoryMinTokens();
        int min = v == null || v <= 0 ? 512 : v;
        return Math.min(min, defaultWindowTokens() / 4);
    }

    private String fit(String key, String text, int[] remaining,
                       Map<String, Integer> used, List<String> trimmed) {
        if (StringUtils.isBlank(text)) {
            used.put(key, 0);
            return "";
        }
        int cost = estimate(text);
        if (cost <= remaining[0]) {
            remaining[0] -= cost;
            used.put(key, cost);
            return text;
        }
        if (remaining[0] < MIN_BLOCK_TOKENS) {
            used.put(key, 0);
            trimmed.add(key);
            return "";
        }
        String cut = truncateToTokens(text, remaining[0]);
        int real = estimate(cut);
        remaining[0] -= real;
        used.put(key, real);
        trimmed.add(key);
        return cut;
    }

    /** 非空片段以两个换行拼接,保持原有分块边界 */
    private static String joinSections(String... sections) {
        StringBuilder sb = new StringBuilder();
        for (String section : sections) {
            if (StringUtils.isBlank(section)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(section);
        }
        return sb.toString();
    }

    private static int estimate(String text) {
        return TokenEstimator.estimate(text);
    }

    /** 按 token 预算二分截断中文/英文混合文本 */
    private static String truncateToTokens(String text, int maxTokens) {
        if (StringUtils.isBlank(text) || maxTokens <= 0) {
            return "";
        }
        int budget = Math.max(1, maxTokens - estimate(TRUNCATE_MARK));
        if (estimate(text) <= budget) {
            return text;
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (estimate(text.substring(0, mid)) <= budget) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + TRUNCATE_MARK;
    }
}
