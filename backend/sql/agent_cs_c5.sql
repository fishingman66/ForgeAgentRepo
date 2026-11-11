-- =====================================================================
-- 智能客服坐席辅助（Agent Assist A1）建表
-- 目标库: forge-vue
-- 幂等: CREATE TABLE IF NOT EXISTS，可重复执行
-- 说明: 推荐记录落库用于采纳埋点 / 质量复盘 / 低质推荐回流 FAQ 飞轮
-- =====================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_cs_seat_suggestion` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL COMMENT '关联会话',
  `ticket_id` BIGINT NULL COMMENT '关联工单(已转人工时)',
  `message_id` BIGINT NULL COMMENT '触发推荐的消息(文本场景为空,机器人回复id)',
  `intent` VARCHAR(64) NULL COMMENT '意图标签(A3 起,A1 为空)',
  `items` TEXT NULL COMMENT '候选话术 JSON 快照(type/title/content/score/sourceId)',
  `actions` TEXT NULL COMMENT '建议操作 JSON 快照(A4 起,A1 为空数组)',
  `top_score` DECIMAL(5,2) NULL COMMENT '最高匹配分',
  `adopted` CHAR(1) DEFAULT '0' COMMENT '是否采纳 0/1',
  `adopt_action` VARCHAR(32) NULL COMMENT '采纳方式 send/copy/ignore',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csssg_conv` (`conversation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席辅助推荐记录';
