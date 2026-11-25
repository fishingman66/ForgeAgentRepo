package com.forgeagent.callcenter.common.acd;

import java.util.Map;

/**
 * ACD 候选坐席的排序输入(零依赖契约层,不引 Spring/Jackson)。
 *
 * <p>字段刻意只收「数值 + 技能等级」——策略不认识任何业务实体,
 * 这样 callcenter 与 customer-service 两侧都能构造同一份上下文。</p>
 *
 * @param candidateId   候选唯一标识(坐席 id 或 userId)
 * @param load          当前负载,越大越忙;为 null 视为 0
 * @param maxLoad       负载上限;为 null 视为 5(与 CsSeatAvailabilityService 同口径)
 * @param skillRanks    技能编码 → 等级;无技能为空 Map
 * @param readyTimeMs   就绪至今时长(毫秒);无数据源为 null,依赖它的策略必须降级
 * @param answerTimes   累计应答次数;无数据源为 null
 * @param talkTimeMs    累计通话时长;无数据源为 null
 * @param afterTimeMs   累计话后时长;无数据源为 null
 */
public record AcdCandidate(Long candidateId, Integer load, Integer maxLoad,
                           Map<String, Integer> skillRanks,
                           Long readyTimeMs, Integer answerTimes,
                           Long talkTimeMs, Long afterTimeMs) {

    public AcdCandidate {
        skillRanks = skillRanks == null ? Map.of() : Map.copyOf(skillRanks);
    }

    /** 当前是否还有余量。 */
    public boolean hasCapacity() {
        int l = load == null ? 0 : load;
        int m = maxLoad == null ? 5 : maxLoad;
        return l < m;
    }

    /** 便利构造:仅负载与技能(ForgeAgent 当前所有可得数据)。 */
    public static AcdCandidate of(Long id, Integer load, Integer maxLoad, Map<String, Integer> ranks) {
        return new AcdCandidate(id, load, maxLoad, ranks, null, null, null, null);
    }
}
