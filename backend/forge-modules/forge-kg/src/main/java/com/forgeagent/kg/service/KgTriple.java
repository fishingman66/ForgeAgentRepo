package com.forgeagent.kg.service;

/**
 * 抽取出的三元组(forge-kg)。
 *
 * @param subject     主语实体名
 * @param relation    关系
 * @param object      宾语实体名
 * @param evidenceRef 溯源引用(记忆域放 memoryId,客服域可放工单/FAQ id),可空
 * @author ForgeAgent
 */
public record KgTriple(String subject, String relation, String object, Long evidenceRef) {
}
