package com.forgeagent.common.web.handler;

import cn.hutool.core.util.RandomUtil;
import com.forgeagent.common.core.utils.SpringUtils;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 异常消息脱敏器:生产环境(prod profile)下对 5xx 错误 message 做敏感特征识别,
 * 命中即替换为通用提示+错误编号,避免 SQL/类路径/绝对路径泄露到前端。
 *
 * <p>使用约束:
 * <ul>
 *   <li>仅对非业务异常(RuntimeException/Exception/MyBatis 异常等)调用,业务 ServiceException 由业务侧自控文案,不经过此类</li>
 *   <li>dev profile 下直接透传原始 message,便于本地调试</li>
 *   <li>触发脱敏时会生成 8 位错误编号,调用方应把编号返回给前端并打到服务端日志,便于反查堆栈</li>
 * </ul>
 *
 * @author ForgeAgent
 */
public final class ErrorMessageSanitizer {

    /**
     * 通用兜底文案,带错误编号占位
     */
    private static final String FALLBACK_TEMPLATE = "系统繁忙，请稍后重试 [错误编号: %s]";

    /**
     * 敏感特征正则:
     * 1) SQL 关键字
     * 2) Java 全限定类名前缀
     * 3) 绝对路径(/home/ /Users/ D:\ 等)
     */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
        "(?i)(\\bselect\\b|\\binsert\\b|\\bupdate\\b|\\bdelete\\b|\\bfrom\\b|\\bwhere\\b" +
            "|\\bjoin\\b|\\btable\\b|\\bunion\\b" +
            "|com\\.|org\\.|java\\.|jakarta\\.|javax\\.|net\\.sf\\.|io\\.netty" +
            "|/home/|/Users/|/var/|/opt/|/etc/|/root/|[A-Za-z]:\\\\" +
            "|\\.java:\\d+|\\.class:\\d+|Caused by|at [a-z]+\\.)"
    );

    private ErrorMessageSanitizer() {
    }

    /**
     * 是否生产环境。spring.profiles.active 含 prod 即视为生产。
     */
    public static boolean isProd() {
        String[] profiles = SpringUtils.getActiveProfiles();
        if (profiles == null || profiles.length == 0) {
            return false;
        }
        return Arrays.stream(profiles).anyMatch(p -> p != null && p.toLowerCase().contains("prod"));
    }

    /**
     * 是否命中敏感特征。
     */
    public static boolean isSensitive(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        return SENSITIVE_PATTERN.matcher(message).find();
    }

    /**
     * 脱敏入口:prod 且命中敏感特征时返回带错误编号的通用文案,否则原样返回。
     *
     * @param rawMessage 原始异常 message
     * @return 脱敏后文案,rawMessage 为 null/空时返回 null
     */
    public static String sanitize(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return rawMessage;
        }
        if (!isProd()) {
            return rawMessage;
        }
        if (!isSensitive(rawMessage)) {
            return rawMessage;
        }
        return String.format(FALLBACK_TEMPLATE, RandomUtil.randomNumbers(8));
    }

    /**
     * 强制脱敏:不判断敏感特征,prod 下直接返回通用文案。用于明确不能外透的 5xx 场景。
     *
     * @param rawMessage 原始 message(仅 dev 返回)
     */
    public static String sanitizeForce(String rawMessage) {
        if (!isProd()) {
            return rawMessage;
        }
        return String.format(FALLBACK_TEMPLATE, RandomUtil.randomNumbers(8));
    }
}
