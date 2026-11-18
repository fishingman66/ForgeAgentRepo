-- =====================================================================
-- 智能客服批次 B（docs/11：B1 信号收口 + B2 转人工规则引擎 + B3 指标补维）
-- 目标库: forge-vue
-- 幂等: 加列/加索引走 information_schema 判断
-- 说明: ① 会话补 last_intent_code/sentiment_streak/last_sentiment_score(每轮信号入账);
--       ② 渠道补 handoff_rules(JSON,转人工规则链配置);
--       ③ 日指标补 repeat_contact_rate/containment_rate;
--       ④ 会话表补 (visitor_id, create_time) 索引支撑 24h 重复进线统计。
--       新库直接用 01-schema(列已折进建表),本脚本仅供存量库升级。
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_b1`;
DROP PROCEDURE IF EXISTS `cs_add_index_b1`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_b1`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CREATE PROCEDURE `cs_add_index_b1`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @cs_sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX ', p_ddl);
        PREPARE cs_stmt FROM @cs_sql;
        EXECUTE cs_stmt;
        DEALLOCATE PREPARE cs_stmt;
    END IF;
END$$
DELIMITER ;

-- B1:每轮信号入账(意图编码 / 负面连续轮数 / 最近情绪分)
CALL `cs_add_column_b1`('agent_cs_conversation', 'last_intent_code',
    '`last_intent_code` VARCHAR(64) NULL COMMENT ''最近一轮意图编码(规则命中,未命中为 NULL)''');
CALL `cs_add_column_b1`('agent_cs_conversation', 'sentiment_streak',
    '`sentiment_streak` INT NOT NULL DEFAULT 0 COMMENT ''访客负面情绪连续轮数(得分不超阈值递增,超阈值清零)''');
CALL `cs_add_column_b1`('agent_cs_conversation', 'last_sentiment_score',
    '`last_sentiment_score` DECIMAL(5,4) NULL COMMENT ''最近一轮访客情绪分([-1,1],负面为负)''');

-- B2:转人工规则链配置(JSON 数组,空则用内置默认:关键词+情绪阈值+重复进线,轮次上限默认关)
CALL `cs_add_column_b1`('agent_cs_channel', 'handoff_rules',
    '`handoff_rules` TEXT NULL COMMENT ''转人工规则链 JSON([{type,enabled,...}]),空用内置默认''');

-- B3:指标补维(重复进线率 / AI 自动解决率 containment)
CALL `cs_add_column_b1`('agent_cs_metric_daily', 'repeat_contact_rate',
    '`repeat_contact_rate` DECIMAL(5,2) NULL COMMENT ''重复进线率(%):当日会话中同访客24h内已有过进线的占比''');
CALL `cs_add_column_b1`('agent_cs_metric_daily', 'containment_rate',
    '`containment_rate` DECIMAL(5,2) NULL COMMENT ''AI 自动解决率(%):未产生转人工工单即终结的会话占比''');

-- 24h 重复进线统计的取数路径
CALL `cs_add_index_b1`('agent_cs_conversation', 'idx_csc_visitor_time',
    '`idx_csc_visitor_time` (`visitor_id`, `create_time`)');

DROP PROCEDURE IF EXISTS `cs_add_column_b1`;
DROP PROCEDURE IF EXISTS `cs_add_index_b1`;
