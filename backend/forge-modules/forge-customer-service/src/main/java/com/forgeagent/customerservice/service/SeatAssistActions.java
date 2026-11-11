package com.forgeagent.customerservice.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 坐席辅助建议动作(A4,方案第八章):推荐卡片旁的「一键执行」白名单。
 *
 * <p>只有三类低风险动作,且全部复用既有写路径——不新造任何「坐席辅助专用」的状态修改入口:</p>
 * <ul>
 *   <li>{@code REPLY} 用候选话术回复客户 → {@code CsTicketService.reply}(仅受理坐席可发);</li>
 *   <li>{@code LINK} 发送候选里的参考链接 → 同样走 reply,正文前置一句说明;</li>
 *   <li>{@code RESOLVE} 标记已解决 → {@code transition(PROCESSING→WAITING_CONFIRM)},
 *       与工作台「标记解决」同口径,终态仍由客户确认推进。</li>
 * </ul>
 *
 * <p>「触发工作流」本期不开放:跨域鉴权与失败回滚成本高,不属于低风险动作。</p>
 *
 * <p>动作在推荐生成时快照进 {@code actions} 字段,执行时按 code 在快照里找——
 * 前端只能执行服务端当时生成过的动作,不能自己拼 payload 制造动作。</p>
 */
public final class SeatAssistActions {

    public static final String REPLY = "REPLY";
    public static final String LINK = "LINK";
    public static final String RESOLVE = "RESOLVE";

    public static final Set<String> ALLOWED = Set.of(REPLY, LINK, RESOLVE);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-./?%&=:#+~]+");

    private SeatAssistActions() {
    }

    /**
     * 按候选内容与工单情况生成动作(纯函数)。
     *
     * <p>无候选内容时不生成 REPLY/LINK——没东西可发就不该给按钮;
     * 工单不存在时不生成 RESOLVE——没有工单就没有「解决」的对象。</p>
     */
    public static List<Map<String, Object>> build(List<Map<String, Object>> items, boolean ticketOpen) {
        List<Map<String, Object>> actions = new ArrayList<>();
        String firstContent = firstContent(items);
        if (firstContent != null) {
            actions.add(action(REPLY, "用该话术回复", Map.of("content", firstContent)));
            String link = firstLink(items);
            if (link != null) {
                actions.add(action(LINK, "发送参考链接",
                    Map.of("content", "相关资料：" + link + "\n" + firstContent, "url", link)));
            }
        }
        if (ticketOpen) {
            actions.add(action(RESOLVE, "标记已解决", Map.of()));
        }
        return actions;
    }

    public static boolean isAllowed(String code) {
        return code != null && ALLOWED.contains(code.toUpperCase());
    }

    /** 在快照里找动作:找不到说明前端在请求服务端从未生成的动作。 */
    public static Map<String, Object> find(List<Map<String, Object>> actions, String code) {
        if (actions == null || code == null) {
            return null;
        }
        for (Map<String, Object> action : actions) {
            if (action != null && code.equalsIgnoreCase(String.valueOf(action.get("code")))) {
                return action;
            }
        }
        return null;
    }

    /** 动作要发送的正文(REPLY / LINK 用);RESOLVE 无正文返回 null。 */
    public static String contentOf(Map<String, Object> action) {
        if (action == null) {
            return null;
        }
        Object payload = action.get("payload");
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object content = map.get("content");
        return content == null ? null : String.valueOf(content);
    }

    /** 候选里第一个 http(s) 链接;没有返回 null。 */
    public static String firstLink(List<Map<String, Object>> items) {
        if (items == null) {
            return null;
        }
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            Object content = item.get("content");
            if (content == null) {
                continue;
            }
            Matcher matcher = URL_PATTERN.matcher(String.valueOf(content));
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    private static String firstContent(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        Object content = items.get(0).get("content");
        if (content == null) {
            return null;
        }
        String text = String.valueOf(content).trim();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, Object> action(String code, String label, Map<String, Object> payload) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("code", code);
        action.put("label", label);
        action.put("payload", payload);
        return action;
    }
}
