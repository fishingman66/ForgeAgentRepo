package com.forgeagent.agent.service.routing;

import com.forgeagent.agent.service.runner.ToolAssembler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 结构化意图路由(P2 / C1)。
 *
 * <p>规则版实现,不额外调用模型:从工具名与描述中提取中文二元组 / 英文词,
 * 与本轮问题做包含匹配,产出可解释的路由计划。计划同时充当本轮工具授权边界——
 * 只有命中的工具会被装配进 Toolkit,模型无法越权调用其余工具。</p>
 *
 * <p>保守策略:一个工具都没匹配上时,allowedTools 退回全部工具,
 * 避免因规则误判把该用的能力过滤掉;缺实体只注入澄清提示,不阻断对话。</p>
 *
 * @author ForgeAgent
 */
@Component
public class IntentRouter {

    private static final Pattern ENTITY = Pattern.compile("([A-Za-z]{2,}-?\\d{3,}|\\d{6,})");
    private static final String[] VAGUE = {"这个", "那个", "它", "上面", "刚才", "之前那个"};

    /** 路由计划 */
    public record Plan(boolean needTool, boolean needRag, boolean directAnswer,
                       boolean needsClarification, double confidence,
                       Set<String> allowedTools, String missingEntity, String summary) {
    }

    public Plan plan(String question, List<ToolAssembler.ToolDef> tools, boolean hasKnowledge) {
        String q = normalize(question);
        Set<String> matched = new LinkedHashSet<>();
        if (tools != null) {
            for (ToolAssembler.ToolDef tool : tools) {
                if (matches(q, tool)) {
                    matched.add(tool.name());
                }
            }
        }
        boolean needTool = !matched.isEmpty();
        Set<String> allowed;
        if (needTool) {
            allowed = matched;
        } else {
            allowed = new LinkedHashSet<>();
            if (tools != null) {
                tools.forEach(t -> allowed.add(t.name()));
            }
        }
        boolean needRag = hasKnowledge;
        boolean directAnswer = !needTool && !needRag;
        boolean vague = containsAny(q, VAGUE);
        boolean hasEntity = ENTITY.matcher(q).find();
        boolean needsClarification = needTool && (q.length() < 6 || (vague && !hasEntity));
        String missingEntity = needsClarification
            ? (hasEntity ? "" : "缺少明确的业务编号或查询对象") : "";
        double confidence = needTool ? 0.8 : (needRag ? 0.6 : 0.5);
        String summary = "工具意图=" + needTool + ",知识检索=" + needRag
            + ",直接回答=" + directAnswer + ",候选工具=" + allowed.size()
            + ",需澄清=" + needsClarification;
        return new Plan(needTool, needRag, directAnswer, needsClarification,
            confidence, allowed, missingEntity, summary);
    }

    /** 按计划裁剪本轮可装配的工具;未注册的工具模型无法调用 */
    public List<ToolAssembler.ToolDef> filterTools(List<ToolAssembler.ToolDef> tools, Plan plan) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = plan.allowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        return tools.stream().filter(t -> allowed.contains(t.name())).toList();
    }

    private boolean matches(String q, ToolAssembler.ToolDef tool) {
        String name = normalize(tool.name());
        String description = normalize(tool.description());
        if (name.length() >= 2 && q.contains(name)) {
            return true;
        }
        if (description.length() < 2) {
            return false;
        }
        int hits = 0;
        for (int i = 0; i + 2 <= description.length(); i++) {
            String gram = description.substring(i, i + 2);
            if (gram.isBlank() || gram.matches("[的了个和与及为在是]+")) {
                continue;
            }
            if (q.contains(gram) && ++hits >= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 去标点与空白、统一小写,避免全半角差异影响匹配 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\p{Punct}·、，。！？；：]+", "")
            .toLowerCase(Locale.ROOT);
    }
}
