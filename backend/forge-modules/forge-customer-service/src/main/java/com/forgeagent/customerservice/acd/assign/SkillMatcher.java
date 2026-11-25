package com.forgeagent.customerservice.acd.assign;

import com.forgeagent.customerservice.domain.CsSeat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能串解析与匹配(激活 CsQueue.requiredSkill 这个此前未参与分配的死字段)。
 *
 * <p>CsSeat.skills 是逗号分隔串且无等级(01-schema.sql:2268),因此"等级"无法从数据还原;
 * 本实现只做<b>必需技能的存在性过滤</b>,多技能命中数作为次级排序权重。
 * requiredSkill 支持逗号分隔的"任一命中"(与 handoffKeywords 同口径),空串视为不限技能。</p>
 */
public final class SkillMatcher {

    private SkillMatcher() {
    }

    /** 解析逗号串为技能编码集合(等级暂记 1,因无 level 字段)。 */
    public static Map<String, Integer> parse(String skills) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (skills == null || skills.isBlank()) {
            return result;
        }
        for (String part : skills.split("[,，]")) {
            String s = part.trim();
            if (!s.isEmpty() && !s.contains(":")) {
                result.put(s, 1);
                continue;
            }
            // 兼容 "skill:3" 写法(为未来等级字段留口),等级非法按 1
            if (s.contains(":")) {
                String[] kv = s.split(":", 2);
                int rank = 1;
                try {
                    rank = Integer.parseInt(kv[1].trim());
                } catch (NumberFormatException ignored) {
                    // 保持 1
                }
                if (!kv[0].trim().isEmpty()) {
                    result.put(kv[0].trim(), rank);
                }
            }
        }
        return result;
    }

    /** 必需技能是否命中(requiredSkill 为空=不限)。 */
    public static boolean matches(String requiredSkill, Map<String, Integer> seatSkills) {
        if (requiredSkill == null || requiredSkill.isBlank()) {
            return true; // 没有技能要求时,不因坐席无技能而过滤(零回退)
        }
        if (seatSkills.isEmpty()) {
            return false; // 有明确技能要求时,无技能坐席不得接(激活 requiredSkill)
        }
        for (String part : requiredSkill.split("[,，]")) {
            String s = part.trim();
            if (!s.isEmpty() && seatSkills.containsKey(s)) {
                return true;
            }
        }
        return false;
    }

    /** 命中数,作为同分时的次级排序权重(命中越多越优先)。 */
    public static int hitCount(String requiredSkill, Map<String, Integer> seatSkills) {
        if (requiredSkill == null || requiredSkill.isBlank() || seatSkills.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String part : requiredSkill.split("[,，]")) {
            if (seatSkills.containsKey(part.trim())) {
                n++;
            }
        }
        return n;
    }

    /** 从实体直接取解析后的技能表。 */
    public static Map<String, Integer> of(CsSeat seat) {
        return seat == null ? Map.of() : parse(seat.getSkills());
    }

    /** 过滤出技能匹配的坐席(保持原顺序)。 */
    public static List<CsSeat> filter(List<CsSeat> seats, String requiredSkill) {
        if (seats == null || seats.isEmpty()) {
            return List.of();
        }
        List<CsSeat> out = new java.util.ArrayList<>();
        for (CsSeat seat : seats) {
            if (matches(requiredSkill, of(seat))) {
                out.add(seat);
            }
        }
        return out;
    }
}
