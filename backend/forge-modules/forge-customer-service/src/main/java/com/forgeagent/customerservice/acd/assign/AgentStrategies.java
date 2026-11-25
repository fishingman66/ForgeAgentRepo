package com.forgeagent.customerservice.acd.assign;

import com.forgeagent.callcenter.common.acd.AcdCandidate;
import com.forgeagent.callcenter.common.acd.AgentStrategy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 7 种坐席分配策略(对齐参考项目 GroupServiceImpl:71-104 的语义与编号)。
 *
 * <p><b>数据可得性事实</b>:ForgeAgent 的 agent_cs_seat 只有 current_load/max_load/skills,
 * 没有 readyTime/answerTimes/talkTime/afterTime 的持久化字段(见 01-schema.sql:2263-2278)。
 * 因此 1/2/3/4/5 五种依赖统计字段的策略在缺数据时<b>显式降级到 min-load 分数</b>,
 * 并在 {@link #degraded(String)} 中标记,不伪造统计值。6 轮选 / 7 随机无需历史字段,可真实生效。</p>
 */
public final class AgentStrategies {

    private AgentStrategies() {
    }

    /** 1 最长空闲:参考 -stateTime;无 state_time 时降级 min-load。 */
    public static final AgentStrategy LONGEST_READY = new DegradingStrategy("longest-ready", c ->
        c.readyTimeMs() == null ? Long.MIN_VALUE : -c.readyTimeMs());

    /** 2 空闲次数最多:无 ready_times 字段,降级 min-load。 */
    public static final AgentStrategy MOST_READY_TIMES = new DegradingStrategy("most-ready-times",
        c -> Long.MIN_VALUE);

    /** 3 最少应答:无 total_answer_times 字段,降级 min-load。 */
    public static final AgentStrategy LEAST_ANSWER = new DegradingStrategy("least-answer",
        c -> Long.MIN_VALUE);

    /** 4 最少通话:无 total_talk_time 字段,降级 min-load。 */
    public static final AgentStrategy LEAST_TALK = new DegradingStrategy("least-talk",
        c -> Long.MIN_VALUE);

    /** 5 话后时长最长:无 total_after_time 字段,降级 min-load。 */
    public static final AgentStrategy MOST_AFTER_TIME = new DegradingStrategy("most-after-time",
        c -> Long.MIN_VALUE);

    /** 6 轮选:参考 PollAssign(-serviceTime);用进程内单调计数取模,无需历史字段。 */
    public static final AgentStrategy ROUND_ROBIN = new RoundRobinStrategy();

    /** 7 随机:参考 RandomAssign,真实生效。 */
    public static final AgentStrategy RANDOM = new AgentStrategy() {
        @Override
        public String code() {
            return "random";
        }

        @Override
        public long score(AcdCandidate c) {
            return ThreadLocalRandom.current().nextLong();
        }
    };

    /** 兜底/默认:最小负载,与现有 assignWaiting 行为完全一致。 */
    public static final AgentStrategy MIN_LOAD = new AgentStrategy() {
        @Override
        public String code() {
            return "min-load";
        }

        @Override
        public long score(AcdCandidate c) {
            return -(c.load() == null ? 0 : c.load());
        }
    };

    private static final Map<String, AgentStrategy> BY_CODE = Map.ofEntries(
        Map.entry("min-load", MIN_LOAD),
        Map.entry("longest-ready", LONGEST_READY),
        Map.entry("most-ready-times", MOST_READY_TIMES),
        Map.entry("least-answer", LEAST_ANSWER),
        Map.entry("least-talk", LEAST_TALK),
        Map.entry("most-after-time", MOST_AFTER_TIME),
        Map.entry("round-robin", ROUND_ROBIN),
        Map.entry("random", RANDOM)
    );

    /** 全部策略(供 Spring 注册);未知编码返回 null 由调用方降级 min-load。 */
    public static AgentStrategy fromCode(String code) {
        if (code == null || code.isBlank()) {
            return MIN_LOAD;
        }
        AgentStrategy s = BY_CODE.get(code.trim().toLowerCase());
        return s == null ? MIN_LOAD : s;
    }

    public static List<AgentStrategy> all() {
        return List.copyOf(BY_CODE.values());
    }

    /** 该策略在当前数据条件下是否降级(运营页/日志用)。 */
    public static boolean degraded(String code) {
        AgentStrategy s = fromCode(code);
        return s instanceof DegradingStrategy ds && ds.degraded();
    }

    /** 需要统计字段但当前缺数据时用 min-load 分数替代的策略基类。 */
    private static final class DegradingStrategy implements AgentStrategy {
        private final String code;
        private final Function<AcdCandidate, Long> raw;
        private volatile boolean degraded;

        private DegradingStrategy(String code, Function<AcdCandidate, Long> raw) {
            this.code = code;
            this.raw = raw;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public long score(AcdCandidate c) {
            Long v = raw.apply(c);
            if (v == null || v == Long.MIN_VALUE) {
                degraded = true;
                return MIN_LOAD.score(c);
            }
            return v;
        }

        boolean degraded() {
            return degraded;
        }
    }

    /** 轮选:每轮推进一次游标,候选按 (id - cursor) 的环绕距离排序,同批比较一致。 */
    private static final class RoundRobinStrategy implements AgentStrategy {
        private final AtomicLong counter = new AtomicLong();
        private volatile long cursor;

        @Override
        public String code() {
            return "round-robin";
        }

        @Override
        public void beginRound() {
            cursor = counter.getAndIncrement();
        }

        @Override
        public long score(AcdCandidate c) {
            long id = c.candidateId() == null ? 0L : c.candidateId();
            // 环绕距离越小越靠前:使游标在候选 id 集合上轮转
            return -Math.floorMod(id - cursor, 1_000_000L);
        }
    }
}
