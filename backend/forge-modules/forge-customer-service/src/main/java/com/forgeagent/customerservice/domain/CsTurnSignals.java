package com.forgeagent.customerservice.domain;

/**
 * B1:每轮对话信号(批次 docs/11-B1)——入口一次计算,全链路共享。
 *
 * <p>此前意图 classify 散落在 5 个旁路点(路由/缓存/槽位/缺口/坐席辅助)各自计算,
 * 主链路不跑;情绪文本侧为零。本记录把每轮信号收敛成一个值对象,
 * 由 {@code CsTurnSignalService} 在 send() 入口算一次并写入会话
 * (last_intent_code / sentiment_streak / last_sentiment_score),供
 * B2 转人工规则引擎与 B3 指标消费。旁路点迁移到消费共享信号是后续演进,不在本批。</p>
 *
 * @param intentCode         规则意图编码(未命中为 null)
 * @param intentName         意图名称(未命中为 null)
 * @param sentimentLabel     情绪标签 NEGATIVE/NEUTRAL/POSITIVE(无分析器时 NEUTRAL)
 * @param sentimentScore     情绪分 [-1,1],负值偏负面(无分析器时 0)
 * @param repeatTotal24h     同访客 24h 内已有过的进线会话数(不含本轮)
 * @param turnCount          会话累计轮数(agent_session.messageCount/2 估算)
 * @param sentimentStreak    负面情绪连续轮数(得分不超阈值递增,超阈值清零)
 */
public record CsTurnSignals(String intentCode, String intentName,
                            String sentimentLabel, double sentimentScore,
                            int repeatTotal24h, int turnCount, int sentimentStreak) {
}
