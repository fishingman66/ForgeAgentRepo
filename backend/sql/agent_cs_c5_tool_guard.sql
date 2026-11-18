-- =====================================================================
-- 智能客服批次 C（docs/11：C1 工具风险分级 + C2 归属校验下推 + C3 动作幂等额度 + C5 调用审计）
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断，建表 IF NOT EXISTS
-- 说明: ① agent_tool 补 risk_level/action_key/daily_limit(风险分级与动作幂等键);
--       ② 渠道补 identity_binding(访客身份绑定方式,未配置时写操作工具一律 deny);
--       ③ 新表 agent_cs_action_log(conversation+action_key 唯一,操作幂等占位)。
--       新库直接用 01-schema(列已折进建表),本脚本仅供存量库升级。
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_c5g`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_c5g`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

-- C1:工具风险分级(READ/WRITE/HIGH_RISK,默认 READ) + 幂等业务键模板(如 refund:{orderNo}) + 日限次
CALL `cs_add_column_c5g`('agent_tool', 'risk_level',
    '`risk_level` VARCHAR(16) NOT NULL DEFAULT ''READ'' COMMENT ''风险分级:READ/WRITE/HIGH_RISK''');
CALL `cs_add_column_c5g`('agent_tool', 'action_key',
    '`action_key` VARCHAR(128) NULL COMMENT ''幂等业务键模板,占位符 {param} 从工具入参取值,空则不做操作幂等''');
CALL `cs_add_column_c5g`('agent_tool', 'daily_limit',
    '`daily_limit` INT NULL COMMENT ''动作级日限次(按 空间+访客+工具 日窗口,空则不限)''');

-- C2:访客身份绑定方式(external_token/phone_claim 等;空=未配置,写操作工具 fail-closed)
CALL `cs_add_column_c5g`('agent_cs_channel', 'identity_binding',
    '`identity_binding` VARCHAR(32) NULL COMMENT ''访客身份绑定方式,空=未配置(WRITE/HIGH_RISK 工具一律 deny)''');

DROP PROCEDURE IF EXISTS `cs_add_column_c5g`;

-- C3:操作幂等日志(conversation_id + action_key 唯一;重复调用直接返回上次结果摘要)
CREATE TABLE IF NOT EXISTS `agent_cs_action_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL COMMENT '客服会话ID',
  `visitor_id` BIGINT NULL,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `tool_name` VARCHAR(128) NOT NULL,
  `action_key` VARCHAR(128) NOT NULL COMMENT '幂等业务键(模板按入参展开)',
  `risk_level` VARCHAR(16) NOT NULL DEFAULT 'WRITE',
  `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED/BLOCKED',
  `result_summary` VARCHAR(500) NULL COMMENT '执行结果摘要(供重复请求回放)',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csal_key` (`conversation_id`, `action_key`),
  KEY `idx_csal_ws` (`workspace_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服动作幂等日志(C3)';
