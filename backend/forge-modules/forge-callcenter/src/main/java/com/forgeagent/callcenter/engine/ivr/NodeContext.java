package com.forgeagent.callcenter.engine.ivr;

import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点上下文(C3):会话级变量表 + SpEL 求值(SmartCall NodeContext 三通道的裁剪版——
 * script 节点已砍,JSR-223 Bindings 通道随之裁掉,SpEL + Map 两通道足够)。
 *
 * <p>变量模板语法统一 {@code ${expr}}:每个占位符用 SpEL 求值后插值拼接,
 * 无占位符则原样返回;SpEL root 即变量表(Map 支持 property 访问),
 * 预置变量:callId / caller / callee / workspaceId / callerUtterance(最近终稿) /
 * ivrLastResult(上一节点结果) / turnEngine / channel 等(会话驱动器装配)。</p>
 */
public class NodeContext {

    private final Map<String, Object> vars = new HashMap<>();
    private final ExpressionParser parser = new SpelExpressionParser();

    public NodeContext(Map<String, Object> initial) {
        if (initial != null) {
            vars.putAll(initial);
        }
    }

    public Object getVar(String key) {
        return vars.get(key);
    }

    public void setVar(String key, Object value) {
        if (value == null) {
            vars.remove(key);
        } else {
            vars.put(key, value);
        }
    }

    /** 变量表只读视图(SpEL root)。 */
    public Map<String, Object> vars() {
        return java.util.Collections.unmodifiableMap(vars);
    }

    /**
     * 模板渲染:提取全部 {@code ${...}} 占位符逐段 SpEL 求值拼接。
     * 求值失败抛 ServiceException(节点级错误,由驱动器落死信)而非静默空串。
     */
    public String render(String template) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int open;
        while ((open = template.indexOf("${", from)) >= 0) {
            int close = template.indexOf('}', open + 2);
            if (close < 0) {
                break;
            }
            out.append(template, from, open);
            out.append(String.valueOf(eval(template.substring(open + 2, close))));
            from = close + 1;
        }
        out.append(template.substring(from));
        return out.toString();
    }

    /** SpEL 求值:root 为变量表(Map 支持 property 访问,需显式注册 MapAccessor)。 */
    public Object eval(String expression) {
        StandardEvaluationContext context = new StandardEvaluationContext(vars);
        context.addPropertyAccessor(new MapAccessor());
        return parser.parseExpression(expression).getValue(context);
    }

    /** 布尔求值(condition 节点),null 视为 false。 */
    public boolean evalBool(String expression) {
        Object value = eval(expression);
        return Boolean.TRUE.equals(value);
    }

    public String str(String key) {
        Object value = vars.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
