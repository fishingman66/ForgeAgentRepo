-- =====================================================================
-- 呼叫中心 C8(运营增强)增量
-- 目标库: forge-vue
-- 幂等: 建表 CREATE TABLE IF NOT EXISTS；加列走 information_schema 判断 + PREPARE
--       (MySQL 无 ADD COLUMN IF NOT EXISTS)，可重复执行
-- 说明: 对齐方案 docs/07 §三 P2。四项能力:转接时间轴 / 质检幂等 /
--       日指标扩维 / 通话画像与呼损回拨清单。
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 1. 转写分片加段类型(转接时间轴) ----------------
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_call_segment'
     AND column_name = 'segment_kind') = 0,
  'ALTER TABLE `agent_cc_call_segment` ADD COLUMN `segment_kind` VARCHAR(24) NOT NULL DEFAULT ''TRANSCRIPT'' COMMENT ''TRANSCRIPT/TRANSFER_RING/TRANSFER_CONNECT'' AFTER `is_final`',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------- 2. 日指标扩维(8 个新口径) ----------------
SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'transfer_count') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `transfer_count` INT DEFAULT 0 COMMENT ''转人工通话数'' AFTER `recording_coverage`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'transfer_rate') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `transfer_rate` DECIMAL(5,2) DEFAULT 0 COMMENT ''转人工率 %'' AFTER `transfer_count`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'abandon_count') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `abandon_count` INT DEFAULT 0 COMMENT ''呼损数(振铃未接通)'' AFTER `transfer_rate`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'abandon_rate') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `abandon_rate` DECIMAL(5,2) DEFAULT 0 COMMENT ''呼损率 %'' AFTER `abandon_count`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'avg_wait_sec') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `avg_wait_sec` INT DEFAULT 0 COMMENT ''平均等待时长(振铃→接听,秒)'' AFTER `abandon_rate`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'negative_calls') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `negative_calls` INT DEFAULT 0 COMMENT ''含负面情绪洞察的通话数'' AFTER `avg_wait_sec`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'negative_rate') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `negative_rate` DECIMAL(5,2) DEFAULT 0 COMMENT ''负面情绪通话占比 %'' AFTER `negative_calls`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'agent_cc_metric_daily'
     AND column_name = 'quality_avg_score') = 0,
  'ALTER TABLE `agent_cc_metric_daily` ADD COLUMN `quality_avg_score` DECIMAL(5,2) DEFAULT 0 COMMENT ''当日语音质检均分(0-100)'' AFTER `negative_rate`', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------- 3. 通话画像快照(每通话一行,幂等) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_call_profile` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `workspace_id` BIGINT NULL,
  `channel_code` VARCHAR(64) NULL,
  `caller_number` VARCHAR(32) NULL COMMENT '已 PII 脱敏',
  `intent_code` VARCHAR(64) NULL COMMENT '挂机全文意图',
  `sentiment_label` VARCHAR(16) NULL COMMENT '通话内最负面的一次情绪标签',
  `sentiment_score` DECIMAL(8,4) NULL,
  `quality_score` INT NULL COMMENT '语音质检分(无工单/无规则为空)',
  `duration_sec` INT NULL,
  `turn_count` INT DEFAULT 0 COMMENT '主叫轮次',
  `transfer_flag` CHAR(1) DEFAULT '0' COMMENT '是否转过人工',
  `resolved_flag` CHAR(1) DEFAULT '0' COMMENT '机器人自助解决(无工单)',
  `tags` VARCHAR(255) NULL COMMENT '逗号分隔标签:负面/转人工/未解决/高风险',
  `summary` VARCHAR(512) NULL COMMENT '一句话摘要(确定性拼装,不含杜撰内容)',
  `payload` TEXT NULL COMMENT 'JSON:意图/情绪分布/提取结果,只存脱敏内容',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccp_call` (`call_id`),
  KEY `idx_cccp_ws` (`workspace_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话画像快照(C8)';

-- ---------------- 4. 呼损回拨清单(每通话一行,幂等) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_callback_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `workspace_id` BIGINT NULL,
  `channel_code` VARCHAR(64) NULL,
  `caller_number` VARCHAR(32) NULL COMMENT '已 PII 脱敏',
  `reason` VARCHAR(32) NOT NULL DEFAULT 'NO_ANSWER' COMMENT 'NO_ANSWER(振铃未接)/ABANDONED(排队放弃)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CALLED/CLOSED',
  `attempt_count` INT DEFAULT 0,
  `last_attempt_at` DATETIME NULL,
  `operator_id` BIGINT NULL,
  `remark` VARCHAR(255) NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccct_call` (`call_id`),
  KEY `idx_ccct_ws_status` (`workspace_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='呼损回拨清单(C8)';
