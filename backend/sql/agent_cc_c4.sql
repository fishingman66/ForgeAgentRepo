-- =====================================================================
-- 呼叫中心 C4(通话洞察管道)增量
-- 目标库: forge-vue
-- 幂等: 全部 CREATE TABLE IF NOT EXISTS，可重复执行
-- 说明: 统一表 agent_cc_call_insight 承载四类洞察(SENTIMENT/INTENT/EXTRACT/QA_REPORT),
--       对齐方案 docs/07 §三 P0-2。payload 只存脱敏后内容(CsPiiMasker 先行)。
--       segment_until_seq 是增量游标(已消费的最大 agent_cc_call_segment.id),只增不减。
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 通话洞察(情绪/意图/提取/质检报告) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_call_insight` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `workspace_id` BIGINT NULL,
  `kind` VARCHAR(16) NOT NULL COMMENT 'SENTIMENT/INTENT/EXTRACT/QA_REPORT',
  `item_key` VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'kind 内多实例键:情绪轮次 turn-<seq>/意图 final/提取字段名/报告类型',
  `segment_until_seq` BIGINT NOT NULL DEFAULT 0 COMMENT '增量游标:已消费的最大 segment.id,单调不减',
  `label` VARCHAR(64) NULL COMMENT '情绪标签 NEGATIVE/NEUTRAL/POSITIVE、意图码、ERROR',
  `score` DECIMAL(8,4) NULL COMMENT '情绪分 [-1,1] 或分类置信度',
  `payload` TEXT NULL COMMENT 'JSON,只存脱敏内容:引擎标识/命中规则/统计摘要',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccci_call_kind_key` (`call_id`, `kind`, `item_key`),
  KEY `idx_ccci_call` (`call_id`, `kind`),
  KEY `idx_ccci_ws_kind` (`workspace_id`, `kind`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话洞察(情绪/意图/提取/质检报告)';
