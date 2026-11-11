-- =====================================================================
-- G 系列: 触发器幂等 / 工作流审批视图与过期清扫 / 工作流 API 暴露
-- 目标库: forge-vue
-- 幂等: 加列/加索引走 information_schema 判断,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_g_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_g_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @forge_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE forge_stmt FROM @forge_sql;
        EXECUTE forge_stmt;
        DEALLOCATE PREPARE forge_stmt;
    END IF;
END$$

CREATE PROCEDURE `forge_g_add_index`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @forge_sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX ', p_ddl);
        PREPARE forge_stmt FROM @forge_sql;
        EXECUTE forge_stmt;
        DEALLOCATE PREPARE forge_stmt;
    END IF;
END$$
DELIMITER ;

-- G3: 触发器投递幂等键(cron=triggerId+调度分钟;webhook=请求头 Idempotency-Key;手动触发不设)
CALL `forge_g_add_column`('agent_workflow_run', 'idempotency_key',
    '`idempotency_key` VARCHAR(128) NULL COMMENT ''幂等键(agent内唯一,防重复投递)'' AFTER `inputs`');
CALL `forge_g_add_index`('agent_workflow_run', 'uk_run_idem',
    '`uk_run_idem` (`agent_id`, `idempotency_key`)');

-- G1: 工作流审批单过期时间(status 枚举已有 expired,补过期时刻;清扫任务据此置 expired + run 置 fail)
CALL `forge_g_add_column`('agent_approval', 'expired_at',
    '`expired_at` DATETIME NULL COMMENT ''审批截止时间(过期清扫置 expired,run 置 fail)'' AFTER `status`');

-- G2: 工作流对外 API 端点(鉴权挂现有 API 密钥体系,Redis 按 key 每分钟限流)
CREATE TABLE IF NOT EXISTS `agent_workflow_endpoint` (
    `id`             BIGINT        NOT NULL                COMMENT '主键',
    `agent_id`       BIGINT        NOT NULL                COMMENT 'AgentID(工作流归属)',
    `workspace_id`   BIGINT        NULL                    COMMENT '工作空间ID',
    `endpoint_code`  VARCHAR(64)   NOT NULL                COMMENT '端点编码(路径段,全局唯一)',
    `name`           VARCHAR(100)  NULL                    COMMENT '端点名称',
    `api_key_id`     BIGINT        NULL                    COMMENT '绑定的 API 密钥ID(空为仅空间内调试)',
    `rate_limit`     INT           NOT NULL DEFAULT 60     COMMENT '每分钟调用上限',
    `timeout_seconds` INT          NOT NULL DEFAULT 60     COMMENT '同步执行超时秒数(1-300)',
    `status`         CHAR(1)       NOT NULL DEFAULT '0'    COMMENT '状态(0启用 1停用)',
    `create_dept`    BIGINT        NULL                    COMMENT '创建部门',
    `create_by`      BIGINT        NULL                    COMMENT '创建者',
    `create_time`    DATETIME      NULL                    COMMENT '创建时间',
    `update_by`      BIGINT        NULL                    COMMENT '更新者',
    `update_time`    DATETIME      NULL                    COMMENT '更新时间',
    `del_flag`       BIGINT        NOT NULL DEFAULT 0      COMMENT '删除标志(0存在 其他删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_endpoint_code` (`endpoint_code`),
    KEY `idx_wfe_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流对外 API 端点';

DROP PROCEDURE IF EXISTS `forge_g_add_column`;
DROP PROCEDURE IF EXISTS `forge_g_add_index`;
