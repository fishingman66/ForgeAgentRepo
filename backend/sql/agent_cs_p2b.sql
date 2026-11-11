-- =====================================================================
-- 智能客服 P2 收尾（死信队列 + 访客附件）
-- 目标库: forge-vue
-- 幂等: 建表 IF NOT EXISTS；附件复用平台已有 agent_chat_attachment(不新建表)
-- =====================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_cs_dead_letter` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `event_type` VARCHAR(32) NOT NULL COMMENT 'HANDOFF_SUMMARY / ATTACHMENT_UPLOAD / SEAT_ASSIST',
  `conversation_id` BIGINT NULL,
  `ticket_id` BIGINT NULL,
  `payload` TEXT NULL COMMENT '重投所需参数(JSON)',
  `error` VARCHAR(500) NULL COMMENT '失败原因(截断)',
  `status` VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/REPLAYED/IGNORED/UNREPLAYABLE',
  `retry_count` INT DEFAULT 0,
  `last_retry_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csdl_status` (`workspace_id`, `status`, `id`),
  KEY `idx_csdl_type` (`workspace_id`, `event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服死信(可重投的业务失败)';
