-- =====================================================================
-- 呼叫中心 C2(级联对话链路)增量
-- 目标库: forge-vue
-- 幂等: 全部 CREATE TABLE IF NOT EXISTS，可重复执行
-- 说明: 不允许打断词组表(方案 docs/07 P0-1①),对齐 SmartCall
--       ivr_answer_no_interrupt 的精确/模糊双模式;不给 agent_cs_* 加表
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 不允许打断词组 ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_voice_no_interrupt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `word` VARCHAR(64) NOT NULL COMMENT '词组(如 嗯/哦/好的)',
  `match_mode` VARCHAR(16) NOT NULL DEFAULT 'exact' COMMENT 'exact=partial 全等 / fuzzy=partial 包含',
  `enabled` CHAR(1) NOT NULL DEFAULT '1',
  `workspace_id` BIGINT NOT NULL DEFAULT 1 COMMENT '归属空间,0=全局',
  `del_flag` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cc_noint_word` (`workspace_id`, `word`, `match_mode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不允许打断词组(C2 barge-in 豁免)';

-- 开发兜底种子(与 CallcenterProperties.noInterruptWords 一致,幂等)
INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT * FROM (SELECT '嗯' AS word, 'exact' AS match_mode, '1' AS enabled, 0 AS workspace_id, 0 AS del_flag, NOW() AS create_time) t
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '嗯' AND match_mode = 'exact' AND del_flag = 0);
INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT * FROM (SELECT '哦', 'exact', '1', 0, 0, NOW()) t
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '哦' AND match_mode = 'exact' AND del_flag = 0);
INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT * FROM (SELECT '好的', 'exact', '1', 0, 0, NOW()) t
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '好的' AND match_mode = 'exact' AND del_flag = 0);
INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT * FROM (SELECT '麻烦', 'fuzzy', '0', 0, 0, NOW()) t
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '麻烦' AND match_mode = 'fuzzy' AND del_flag = 0);
