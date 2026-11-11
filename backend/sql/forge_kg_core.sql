-- =====================================================================
-- forge-kg 知识图谱核心表(MySQL 存图后端)
-- 目标库: forge-vue
--
-- 说明: 图谱能力已抽成独立模块 forge-kg,不只服务模型记忆,
--       也为智能客服提供领域知识(产品/故障/话术/意图)。
--       因此范围不写死 agent_id,而是通用三元组
--       (scope_type, scope_id, user_id):
--         agent     + agentId      -> 长期记忆(M3)
--         workspace + workspaceId  -> 客服领域图谱(后续接入)
--         channel   + channelId    -> 渠道级知识(可选)
--
-- 关键设计:
--   1) user_id 用 0 表示「范围共享级」,不用 NULL —— MySQL 唯一索引对 NULL 不去重,
--      用 NULL 会让共享节点/边产生重复行,upsert 失效。
--   2) 唯一键就是 upsert 的冲突键:
--      node(scope_type, scope_id, user_id, name_key)
--      edge(scope_type, scope_id, user_id, source_key, relation, target_key)
--   3) evidence_ref 是通用溯源引用(记忆ID / 工单ID / FAQ ID),不做外键。
--   4) 生产换 Neo4j 时可不建这两张表,只需提供 KgStore 实现。
--
-- 幂等: CREATE TABLE IF NOT EXISTS,可重复执行
-- 迁移: M3 初版曾建 agent_memory_kg_node / agent_memory_kg_edge 两张
--       以 agent_id 命名的表(仅本机 dev 建过、无存量数据)。若确认无需保留,
--       可单独执行文件末尾注释里的两条 DROP。
-- =====================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `kg_node`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scope_type`  VARCHAR(32)  NOT NULL COMMENT '范围类型 agent/workspace/channel',
    `scope_id`    BIGINT       NOT NULL COMMENT '范围ID',
    `user_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '用户ID,0=范围共享级',
    `name_key`    VARCHAR(128) NOT NULL COMMENT '实体归一化键(小写去标点)',
    `name`        VARCHAR(128) NOT NULL COMMENT '实体展示名',
    `node_type`   VARCHAR(32)  NOT NULL DEFAULT 'entity' COMMENT '实体类型',
    `salience`    DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '实体显著度(出现次数加权)',
    `last_seen`   DATETIME              DEFAULT NULL COMMENT '最近一次出现时间',
    `create_time` DATETIME              DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kg_node` (`scope_type`, `scope_id`, `user_id`, `name_key`),
    KEY `idx_kg_node_scan` (`scope_type`, `scope_id`, `user_id`, `salience`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识图谱-实体节点(forge-kg)';

CREATE TABLE IF NOT EXISTS `kg_edge`
(
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scope_type`   VARCHAR(32)    NOT NULL COMMENT '范围类型',
    `scope_id`     BIGINT         NOT NULL COMMENT '范围ID',
    `user_id`      BIGINT         NOT NULL DEFAULT 0 COMMENT '用户ID,0=范围共享级',
    `source_key`   VARCHAR(128)   NOT NULL COMMENT '主语实体归一化键',
    `relation`     VARCHAR(64)    NOT NULL COMMENT '关系(归一化动词短语)',
    `target_key`   VARCHAR(128)   NOT NULL COMMENT '宾语实体归一化键',
    `weight`       DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '关系权重(重复出现累加)',
    `evidence_ref` BIGINT                  DEFAULT NULL COMMENT '通用溯源引用(记忆/工单/FAQ ID)',
    `last_seen`    DATETIME                DEFAULT NULL COMMENT '最近一次出现时间',
    `create_time`  DATETIME                DEFAULT NULL COMMENT '创建时间',
    `update_time`  DATETIME                DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kg_edge` (`scope_type`, `scope_id`, `user_id`, `source_key`, `relation`, `target_key`),
    KEY `idx_kg_edge_source` (`scope_type`, `scope_id`, `user_id`, `source_key`, `weight`),
    KEY `idx_kg_edge_target` (`scope_type`, `scope_id`, `user_id`, `target_key`, `weight`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识图谱-关系边(forge-kg)';

-- 旧版(2026-11-10 M3 初版,仅本机 dev)遗留表清理,确认无数据后手动执行:
-- DROP TABLE IF EXISTS `agent_memory_kg_edge`;
-- DROP TABLE IF EXISTS `agent_memory_kg_node`;
