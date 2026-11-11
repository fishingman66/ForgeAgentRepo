package com.forgeagent.kg.service;

/**
 * 图谱实体节点(forge-kg)。
 *
 * @param key      归一化唯一键
 * @param name     展示名
 * @param type     实体类型 entity/person/project/tool/product...
 * @param salience 显著度
 * @author ForgeAgent
 */
public record KgNode(String key, String name, String type, double salience) {
}
