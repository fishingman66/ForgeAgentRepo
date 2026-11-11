package com.forgeagent.agent.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流表达式引擎(体检质量基建批次从 AgentWorkflowServiceImpl 提取的纯函数集合)。
 *
 * <p>变量插值与条件求值是引擎中覆盖面最广的两个原语(模板文本、URL、条件分支、
 * code 输入均经此处理),提取为无依赖纯函数以支撑单元测试锁定行为。</p>
 *
 * @author ForgeAgent
 */
public final class WorkflowExpressionEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern PATH_INDEX_PATTERN = Pattern.compile("([^\\[\\]]*)\\[(\\d+)]");

    private WorkflowExpressionEngine() {
    }

    /**
     * 变量插值:{{start.key}} / {{节点名.output}},未命中的占位符保留原样;
     * 支持 {{a.b[0].c}} 点路径 + 数组下标对 JSON 字段深取
     */
    public static String interpolate(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String resolved = resolveValue(key, vars);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                resolved != null ? resolved : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析变量表达式。完整键优先;否则尝试把表达式按「.」切段,前 i 段作为变量键
     * (兼容 节点名.output 这类含点键),剩余段对变量值做 JSON 深取(对象成员 + 数组下标)。
     * 取不到返回 null,调用方保留原占位符。
     */
    private static String resolveValue(String expr, Map<String, String> vars) {
        if (vars.containsKey(expr)) {
            return vars.get(expr);
        }
        List<String> tokens = new ArrayList<>();
        for (String part : expr.split("\\.")) {
            Matcher im = PATH_INDEX_PATTERN.matcher(part);
            if (im.matches() && !im.group(1).isEmpty()) {
                tokens.add(im.group(1));
                tokens.add("[" + im.group(2) + "]");
            } else {
                tokens.add(part);
            }
        }
        for (int i = 1; i < tokens.size(); i++) {
            String prefix = String.join(".", tokens.subList(0, i));
            if (!vars.containsKey(prefix)) {
                continue;
            }
            JsonNode cur;
            try {
                String raw = vars.get(prefix);
                cur = raw == null ? null : MAPPER.readTree(raw);
            } catch (Exception e) {
                continue;
            }
            if (cur == null || cur.isMissingNode()) {
                continue;
            }
            boolean ok = true;
            for (int j = i; j < tokens.size(); j++) {
                Matcher im = PATH_INDEX_PATTERN.matcher(tokens.get(j));
                String name = im.matches() ? im.group(1) : tokens.get(j);
                int index = im.matches() ? Integer.parseInt(im.group(2)) : -1;
                if (!name.isEmpty()) {
                    if (!cur.isObject() || cur.path(name).isMissingNode()) {
                        ok = false;
                        break;
                    }
                    cur = cur.path(name);
                }
                if (index >= 0) {
                    if (!cur.isArray() || index >= cur.size()) {
                        ok = false;
                        break;
                    }
                    cur = cur.path(index);
                }
            }
            if (ok) {
                return cur.isValueNode() ? cur.asText() : cur.toString();
            }
        }
        return null;
    }

    /**
     * 条件表达式求值,返回 null 表示语法不支持。
     * 运算符按长词优先匹配:not contains / contains / == / != / >= / <= / > / <;
     * 比较两侧先去引号,均可解析为数字时按数值比较,否则按字符串比较。
     */
    public static Boolean evalCondition(String resolved) {
        String[] ops = {"not contains", "contains", "==", "!=", ">=", "<=", ">", "<"};
        for (String op : ops) {
            int idx = resolved.indexOf(op);
            if (idx > 0) {
                String left = stripQuotes(resolved.substring(0, idx).trim());
                String right = stripQuotes(resolved.substring(idx + op.length()).trim());
                return switch (op) {
                    case "not contains" -> !left.contains(right);
                    case "contains" -> left.contains(right);
                    case "==" -> compareValues(left, right) == 0;
                    case "!=" -> compareValues(left, right) != 0;
                    case ">" -> compareValues(left, right) > 0;
                    case ">=" -> compareValues(left, right) >= 0;
                    case "<" -> compareValues(left, right) < 0;
                    case "<=" -> compareValues(left, right) <= 0;
                    default -> null;
                };
            }
        }
        return null;
    }

    private static String stripQuotes(String s) {
        return s.replaceAll("^['\"]|['\"]$", "");
    }

    /** 数值优先比较:两侧均可解析为数字时按数值,否则按字符串字典序 */
    private static int compareValues(String left, String right) {
        try {
            return Double.compare(Double.parseDouble(left), Double.parseDouble(right));
        } catch (NumberFormatException e) {
            return left.compareTo(right);
        }
    }
}
