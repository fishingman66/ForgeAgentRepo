package com.forgeagent.customerservice.acd;

import com.forgeagent.customerservice.acd.assign.SkillMatcher;
import com.forgeagent.customerservice.service.CsSkillService;
import com.forgeagent.customerservice.domain.CsSeat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1 结构化技能模型契约测试:投影串解析、等级权重、单组上限。
 */
@Tag("dev")
class SkillGroupModelTest {

    @Test
    void projectedRankStringParsedByLegacyMatcher() {
        // 写入侧投影 "skill:rank" 串,P0-2 的 SkillMatcher.parse 已支持 → 老读路径自动拿到等级
        Map<String, Integer> ranks = SkillMatcher.parse("refund:3,tech:2,invoice");
        assertEquals(3, ranks.get("refund"));
        assertEquals(2, ranks.get("tech"));
        assertEquals(1, ranks.get("invoice"), "无等级按 1");

        CsSeat seat = new CsSeat();
        seat.setSkills("refund:3,tech:2");
        assertEquals(3, SkillMatcher.of(seat).get("refund"), "实体读路径也拿到真实等级");
    }

    @Test
    void rankWeightBreaksTieBySkillLevel() {
        // 老数据 rank 恒 1 → 权重退化为命中数(零回退)
        assertEquals(2, CsTicketServiceAccess.weight("refund,tech", Map.of("refund", 1, "tech", 1)));
        // 新数据高等级优先
        assertEquals(5, CsTicketServiceAccess.weight("refund,tech", Map.of("refund", 3, "tech", 2)));
        assertEquals(0, CsTicketServiceAccess.weight(null, Map.of("refund", 3)));
        assertEquals(0, CsTicketServiceAccess.weight("refund", Map.of()));
    }

    @Test
    void groupSkillLimitConstant() {
        assertEquals(10, CsSkillService.MAX_SKILLS_PER_GROUP,
            "单组技能上限对齐参考 GroupServiceImpl:71-73");
    }

    @Test
    void legacyCommaStringStillWorks() {
        // 无结构化表时:纯技能名仍可解析、匹配
        Map<String, Integer> ranks = SkillMatcher.parse("refund,tech");
        assertTrue(SkillMatcher.matches("refund", ranks));
        assertTrue(!SkillMatcher.matches("invoice", ranks));
    }

    /** 通过反射调包级静态方法,避免为测试改生产可见性。 */
    private static final class CsTicketServiceAccess {
        static int weight(String required, Map<String, Integer> ranks) {
            try {
                var m = com.forgeagent.customerservice.service.CsTicketService.class
                    .getDeclaredMethod("skillRankWeight", String.class, Map.class);
                m.setAccessible(true);
                return (Integer) m.invoke(null, required, ranks);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
