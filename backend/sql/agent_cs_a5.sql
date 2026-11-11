-- =====================================================================
-- 智能客服 A5(坐席辅助度量)建表与扩展
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断；建表 IF NOT EXISTS；菜单按 menu_id
-- 说明: ① 日指标按「推荐条数」口径聚合(采纳率 = 采纳条数 / 推荐条数)；
--       ② 触发原文落 trigger_text，零采纳回流时作为待审 FAQ 的问题；
--       ③ recycled 标记该推荐已回流过，避免重复扫。
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_a5`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_a5`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @cs_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE cs_stmt FROM @cs_sql;
        EXECUTE cs_stmt;
        DEALLOCATE PREPARE cs_stmt;
    END IF;
END$$
DELIMITER ;

CALL `cs_add_column_a5`('agent_cs_seat_suggestion', 'trigger_text',
    '`trigger_text` VARCHAR(500) NULL COMMENT "触发原文(文本消息/转写分片)，回流时作为 FAQ 问题"');
CALL `cs_add_column_a5`('agent_cs_seat_suggestion', 'recycled',
    '`recycled` CHAR(1) DEFAULT ''0'' COMMENT "是否已零采纳回流 1/0"');

DROP PROCEDURE IF EXISTS `cs_add_column_a5`;

CREATE TABLE IF NOT EXISTS `agent_cs_assist_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `stat_date` DATE NOT NULL,
  `suggestion_count` INT DEFAULT 0 COMMENT '推荐条数(口径基数)',
  `adopted_count` INT DEFAULT 0 COMMENT '采纳条数(含动作执行)',
  `action_count` INT DEFAULT 0 COMMENT '动作执行条数',
  `intent_count` INT DEFAULT 0 COMMENT '带意图标签条数',
  `recycled_count` INT DEFAULT 0 COMMENT '零采纳回流条数',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csassistmetric_ws_date` (`workspace_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席辅助日指标';

-- 运营菜单:坐席辅助看板(挂在「智能客服」目录下)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040011, '辅助看板', 1761400000000040001, 7, 'assist-board',
       'customerservice/AssistBoard', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:assist:metric', 'chart', null, null, 1, now(), '推荐采纳率/意图命中/零采纳回流'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040011);
