-- =====================================================================
-- P3 / M4: 记忆治理(L4 归档 + L5 遗忘/配额/TTL) + 客服检索扩展指标
-- 目标库: forge-vue
--
-- 内容:
--   1) agent_message_archive  消息归档表(L4,与 agent_message 同构,归档原因 +
--      owner_user_id 冗余列供遗忘级联;向量/图谱不动)
--   2) agent_memory_archive   记忆归档表(TTL 过期 / 配额淘汰的记忆进这里,
--      不归档向量列——重新激活时由补偿扫描重算)
--   3) agent_memory 加 expire_at(可选 TTL;NULL=永久)
--   4) agent_info 加 memory_quota(每 agent+user 记忆条数上限;NULL/0=不限)
--   5) agent_cs_kg_expand_metric  图谱检索扩展效果日聚合(对比开/关的零命中率)
--
-- 设计要点:
--   - 归档保留原 id(便于对账),不用自增;
--   - 遗忘级联:agent_memory(含向量三列) → kg_node/kg_edge → 两张归档表,
--     归档行冗余 owner_user_id / agent_id+user_id,遗忘时按归属直删;
--   - 治理任务默认关闭(memory.governance.enabled),scan-ms 必须 >0(@Scheduled 坑)。
-- 幂等: 建表 IF NOT EXISTS,加列走 information_schema 判断
-- =====================================================================
SET NAMES utf8mb4;

-- ---------- 1) 消息归档表(L4) ----------
CREATE TABLE IF NOT EXISTS `agent_message_archive`
(
    `id`              BIGINT      NOT NULL COMMENT '原 agent_message.id',
    `session_id`      BIGINT      NOT NULL COMMENT '会话ID',
    `role`            VARCHAR(10)  DEFAULT NULL COMMENT '角色(user/assistant)',
    `content`         TEXT         DEFAULT NULL COMMENT '消息内容',
    `ext_json`        MEDIUMTEXT   DEFAULT NULL COMMENT '扩展信息',
    `feedback`        VARCHAR(10)  DEFAULT NULL COMMENT '用户反馈',
    `feedback_comment` VARCHAR(500) DEFAULT NULL COMMENT '反馈备注',
    `feedback_tags`   VARCHAR(500) DEFAULT NULL COMMENT '反馈标签',
    `feedback_time`   DATETIME     DEFAULT NULL COMMENT '反馈时间',
    `citations`       TEXT         DEFAULT NULL COMMENT '引用溯源',
    `create_dept`     BIGINT       DEFAULT NULL COMMENT '创建部门',
    `create_by`       BIGINT       DEFAULT NULL COMMENT '创建者',
    `create_time`     DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_by`       BIGINT       DEFAULT NULL COMMENT '更新者',
    `update_time`     DATETIME     DEFAULT NULL COMMENT '更新时间',
    `attachments`     TEXT         DEFAULT NULL COMMENT '图片附件',
    `client_msg_id`   VARCHAR(64)  DEFAULT NULL COMMENT '客户端幂等键',
    `sender_type`     VARCHAR(16)  DEFAULT NULL COMMENT '发送者类型',
    `ticket_id`       BIGINT       DEFAULT NULL COMMENT '关联工单ID',
    `owner_user_id`   BIGINT       DEFAULT NULL COMMENT '会话归属用户(遗忘级联用,归档时冗余)',
    `archive_reason`  VARCHAR(32)  DEFAULT NULL COMMENT '归档原因 RETENTION',
    `archive_time`    DATETIME     DEFAULT NULL COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_msg_arch_session` (`session_id`),
    KEY `idx_msg_arch_user` (`owner_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话消息归档(P3/M4 L4,主库减重,摘要与抽取不回溯)';

-- ---------- 2) 记忆归档表(TTL/配额/遗忘留痕) ----------
CREATE TABLE IF NOT EXISTS `agent_memory_archive`
(
    `id`               BIGINT      NOT NULL COMMENT '原 agent_memory.id',
    `agent_id`         BIGINT      NOT NULL COMMENT 'AgentID',
    `workspace_id`     BIGINT       DEFAULT NULL COMMENT '工作空间ID',
    `user_id`          BIGINT       DEFAULT NULL COMMENT '用户ID,NULL=平台级',
    `content`          VARCHAR(1000) DEFAULT NULL COMMENT '记忆内容',
    `fact_key`         VARCHAR(128) DEFAULT NULL COMMENT '事实键',
    `importance`       INT          DEFAULT NULL COMMENT '重要性',
    `hit_count`        INT          DEFAULT NULL COMMENT '命中次数',
    `source_session_id` BIGINT      DEFAULT NULL COMMENT '来源会话',
    `archive_reason`   VARCHAR(32)  DEFAULT NULL COMMENT '归档原因 TTL/QUOTA/FORGET',
    `archive_time`     DATETIME     DEFAULT NULL COMMENT '归档时间',
    `create_time`      DATETIME     DEFAULT NULL COMMENT '原创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_mem_arch_scope` (`agent_id`, `user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='长期记忆归档(P3/M4,TTL过期/配额淘汰/遗忘)';

-- ---------- 3) agent_memory 加 expire_at ----------
DROP PROCEDURE IF EXISTS `forge_p3_m4_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_p3_m4_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @forge_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE forge_stmt FROM @forge_sql;
        EXECUTE forge_stmt;
        DEALLOCATE PREPARE forge_stmt;
    END IF;
END$$
DELIMITER ;

CALL `forge_p3_m4_add_column`('agent_memory', 'expire_at',
    '`expire_at` DATETIME NULL COMMENT ''TTL 过期时间,NULL=永久;注入过滤+治理任务归档'' AFTER `last_hit_at`');
CALL `forge_p3_m4_add_column`('agent_info', 'memory_quota',
    '`memory_quota` INT NULL COMMENT ''每 agent+user 记忆条数上限,NULL/0=不限;超限按评分淘汰进归档''');
CALL `forge_p3_m4_add_column`('agent_session', 'pinned',
    '`pinned` CHAR(1) NULL DEFAULT ''0'' COMMENT ''是否置顶/保留(归档跳过)''');

DROP PROCEDURE IF EXISTS `forge_p3_m4_add_column`;

-- ---------- 5) 客服检索扩展指标日聚合 ----------
CREATE TABLE IF NOT EXISTS `agent_cs_kg_expand_metric`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workspace_id`      BIGINT      NOT NULL COMMENT '工作空间ID',
    `stat_date`         DATE        NOT NULL COMMENT '统计日期',
    `total_searches`    BIGINT      NOT NULL DEFAULT 0 COMMENT '知识检索总次数',
    `expanded_searches` BIGINT      NOT NULL DEFAULT 0 COMMENT '带图谱扩展词的检索次数',
    `zero_hit_total`    BIGINT      NOT NULL DEFAULT 0 COMMENT '零命中总次数',
    `zero_hit_expanded` BIGINT      NOT NULL DEFAULT 0 COMMENT '扩展后仍零命中次数',
    `update_time`       DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kg_metric` (`workspace_id`, `stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='图谱辅助检索效果日聚合(P3/M4,对比开/关的零命中率)';
