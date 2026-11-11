-- =====================================================================
-- 智能客服 C3: 渠道接入列扩展 + 渠道菜单
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断,菜单按 menu_id 判断,可重复执行
-- 说明: 渠道消息幂等复用 agent_cs_message_receipt(client_msg_id 存渠道 msgId),不建新表
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_c3`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_c3`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

-- 渠道回调验签密钥(公众号 token / 企微 token 或 EncodingAESKey 占位)
CALL `cs_add_column_c3`('agent_cs_channel', 'channel_secret',
    '`channel_secret` VARCHAR(128) NULL COMMENT "渠道回调验签密钥"');

DROP PROCEDURE IF EXISTS `cs_add_column_c3`;

-- 渠道接入管理菜单
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040007, '渠道接入', 1761400000000040001, 6, 'channels',
       'customerservice/Channels', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:channel:list', 'link', null, null, 1, now(), '网页挂件 / 公众号 / 企微回调配置'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040007);
