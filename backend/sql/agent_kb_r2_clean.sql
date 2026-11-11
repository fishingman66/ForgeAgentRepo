-- ----------------------------
-- R2-2 清洗层(2026-11-11)
-- agent_knowledge.clean_rules:清洗规则 JSON(NULL = 全默认)
--   {"headerFooter":true,"blankNormalize":true,"nfkc":false,"piiMask":false,
--    "regexReplace":[{"pattern":"...","replacement":"..."}]}
-- 默认口径:页眉页脚检测开、空白归一开、NFKC 关(会把全角逗号句号一并半角化,中文语料慎用)、PII 脱敏关
-- ----------------------------

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge` ADD COLUMN `clean_rules` json DEFAULT NULL COMMENT ''解析清洗规则 JSON,NULL=默认(headerFooter/blankNormalize 开,nfkc/piiMask 关)'' AFTER `split_strategy`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge' AND COLUMN_NAME = 'clean_rules');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
