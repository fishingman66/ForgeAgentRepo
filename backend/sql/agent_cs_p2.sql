-- =====================================================================
-- 智能客服 P2 增强（会话级成本口径 + 配额）
-- 目标库: forge-vue
-- 幂等: 建表 IF NOT EXISTS，可重复执行
-- 说明: 成本按「客服会话」聚合 token，不落单价快照——金额只给参考值，
--       缺单价时只报 token 不报金额（不伪造成本，这是同类项目踩过的坑）。
-- =====================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_cs_cost_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `stat_date` DATE NOT NULL,
  `conversation_count` INT DEFAULT 0 COMMENT '当日有模型调用的客服会话数',
  `call_count` INT DEFAULT 0 COMMENT '模型调用次数',
  `prompt_tokens` BIGINT DEFAULT 0,
  `completion_tokens` BIGINT DEFAULT 0,
  `total_tokens` BIGINT DEFAULT 0,
  `estimated_count` INT DEFAULT 0 COMMENT '估算 token 的调用数(非实测)',
  `cost_amount` DECIMAL(18,6) NULL COMMENT '参考金额,未配单价时为空',
  `currency` VARCHAR(8) NULL,
  `resolved_count` INT DEFAULT 0 COMMENT '当日结案工单数(算每次解决成本用)',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cscost_ws_date` (`workspace_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话日成本';
