package com.forgeagent.customerservice.acd;

import com.forgeagent.callcenter.common.acd.AcdCandidate;
import com.forgeagent.callcenter.common.acd.AgentStrategy;
import com.forgeagent.customerservice.acd.assign.AgentStrategies;
import com.forgeagent.customerservice.acd.assign.SkillMatcher;
import com.forgeagent.customerservice.acd.lineup.LineupStrategies;
import com.forgeagent.customerservice.domain.CsSeat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 ACD 多策略 + 技能匹配契约测试。
 *
 * <p>锁定三件事:①7 种策略编码可解析且默认 min-load 不回退;
 * ②无数据源策略显式降级(不伪造统计值);③requiredSkill 从死字段变为真实过滤器。</p>
 */
@Tag("dev")
class AcdStrategyTest {

    private static AcdCandidate seat(long id, int load, Map<String, Integer> skills) {
        return AcdCandidate.of(id, load, 5, skills);
    }

    // ---------------- ① 7 种策略可选且默认兼容 ----------------

    @Test
    void allSevenStrategiesResolvableDefaultIsMinLoad() {
        for (String code : List.of("longest-ready", "most-ready-times", "least-answer",
            "least-talk", "most-after-time", "round-robin", "random")) {
            assertTrue(AgentStrategies.fromCode(code) != null, code + " 可解析");
            assertEquals(code, AgentStrategies.fromCode(code).code());
        }
        assertEquals("min-load", AgentStrategies.fromCode(null).code(), "null → 默认 min-load");
        assertEquals("min-load", AgentStrategies.fromCode("unknown").code(), "未知 → 默认 min-load 零回退");
        assertEquals(8, AgentStrategies.all().size(), "7 种 + min-load 兜底");
    }

    @Test
    void minLoadPrefersLowerLoad() {
        AgentStrategy s = AgentStrategies.MIN_LOAD;
        assertTrue(s.score(seat(1, 0, Map.of())) > s.score(seat(2, 3, Map.of())),
            "负载小的分数更高");
    }

    @Test
    void degradedStrategiesFallBackToMinLoadWithoutStats() {
        // 无 readyTimeMs/answerTimes/talkTime/afterTime 数据时必须降级到 min-load 分数
        for (String code : List.of("longest-ready", "most-ready-times", "least-answer",
            "least-talk", "most-after-time")) {
            AgentStrategy s = AgentStrategies.fromCode(code);
            AcdCandidate light = seat(1, 0, Map.of());
            AcdCandidate busy = seat(2, 3, Map.of());
            assertTrue(s.score(light) > s.score(busy), code + " 缺数据降级按负载排序");
            assertTrue(AgentStrategies.degraded(code), code + " 标记降级");
        }
        // 参考 LongReadyAssign 原样语义:score = -stateTime,空闲越久分数越小
        // (按参考 AgentQueue.compareTo 降序,这里是刻意保持与参考一致,不擅自"修正"符号)
        AgentStrategy lr = AgentStrategies.LONGEST_READY;
        AcdCandidate longIdle = new AcdCandidate(1L, 2, 5, Map.of(), 600_000L, null, null, null);
        AcdCandidate justReady = new AcdCandidate(2L, 0, 5, Map.of(), 1_000L, null, null, null);
        assertTrue(lr.score(longIdle) < lr.score(justReady), "有数据时使用参考 -stateTime 语义");
    }

    @Test
    void roundRobinRotatesAcrossRounds() {
        AgentStrategy rr = AgentStrategies.ROUND_ROBIN;
        AcdCandidate a = seat(10, 0, Map.of());
        AcdCandidate b = seat(11, 0, Map.of());

        rr.beginRound(); // cursor=0
        assertTrue(rr.score(a) > rr.score(b), "cursor=0 候选 10 优先");

        // 再推进 10 次到 cursor=11
        for (int k = 0; k < 11; k++) {
            rr.beginRound();
        }
        assertTrue(rr.score(b) > rr.score(a), "cursor=11 候选 11 优先(轮转)");
    }

    // ---------------- ② 技能过滤激活死字段 ----------------

    @Test
    void requiredSkillFiltersEligibleSeats() {
        CsSeat refund = seatEntity(1L, "refund,after-sale");
        CsSeat tech = seatEntity(2L, "tech");
        CsSeat none = seatEntity(3L, null);

        List<CsSeat> filtered = SkillMatcher.filter(List.of(refund, tech, none), "refund");
        assertEquals(1, filtered.size(), "仅退款技能坐席可接");
        assertEquals(1L, filtered.get(0).getId());

        assertTrue(SkillMatcher.matches("refund", SkillMatcher.of(refund)));
        assertFalse(SkillMatcher.matches("refund", SkillMatcher.of(tech)));
        assertFalse(SkillMatcher.matches("refund", SkillMatcher.of(none)));
    }

    @Test
    void emptyRequiredSkillKeepsAllSeatsZeroRegression() {
        CsSeat refund = seatEntity(1L, "refund");
        CsSeat none = seatEntity(2L, null);
        assertEquals(2, SkillMatcher.filter(List.of(refund, none), null).size(),
            "无技能要求时不过滤(原行为)");
        assertEquals(2, SkillMatcher.filter(List.of(refund, none), "  ").size());
    }

    @Test
    void skillHitCountBreaksTies() {
        assertEquals(2, SkillMatcher.hitCount("refund,tech", Map.of("refund", 1, "tech", 1)));
        assertEquals(1, SkillMatcher.hitCount("refund,tech", Map.of("refund", 1)));
    }

    @Test
    void skillWithRankSyntaxParsed() {
        Map<String, Integer> ranks = SkillMatcher.parse("refund:3,tech:1,bad:x");
        assertEquals(3, ranks.get("refund"));
        assertEquals(1, ranks.get("tech"));
        assertEquals(1, ranks.get("bad"), "非法等级按 1");
    }

    // ---------------- ③ 排队策略 ----------------

    @Test
    void lineupVipOverridesFifoButFifoKeepsChronological() {
        long now = System.currentTimeMillis();
        // FIFO:早入队优先(分数更大)
        assertTrue(LineupStrategies.FIFO.score("NORMAL", now - 1000)
            > LineupStrategies.FIFO.score("NORMAL", now), "FIFO 早入队优先");
        // VIP:高优先级压过早入队
        assertTrue(LineupStrategies.VIP.score("VIP", now)
            > LineupStrategies.VIP.score("NORMAL", now - 10000), "VIP 压过 FIFO");
        assertEquals(0, LineupStrategies.FIFO.score("VIP", now)
            - LineupStrategies.FIFO.score("NORMAL", now), "FIFO 不看优先级(默认不回退)");
        assertEquals("fifo", LineupStrategies.fromCode(null).code());
        assertEquals("fifo", LineupStrategies.fromCode("bad").code());
    }

    private CsSeat seatEntity(long id, String skills) {
        CsSeat seat = new CsSeat();
        seat.setId(id);
        seat.setSkills(skills);
        seat.setOnline("1");
        return seat;
    }
}
