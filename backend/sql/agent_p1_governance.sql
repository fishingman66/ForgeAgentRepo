-- =====================================================================
-- P1: Agent 版本治理 / PromptOps / 效果评测 / 发布门禁
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断,建表用 IF NOT EXISTS,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_p1_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_p1_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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
DELIMITER ;

CALL `forge_p1_add_column`('agent_info', 'version',
    '`version` INT NOT NULL DEFAULT 1 COMMENT ''配置版本号(每次编辑递增)'' AFTER `status`');
CALL `forge_p1_add_column`('agent_info', 'published_version',
    '`published_version` INT NULL COMMENT ''当前生效的发布版本号'' AFTER `version`');
CALL `forge_p1_add_column`('agent_info', 'config_hash',
    '`config_hash` VARCHAR(64) NULL COMMENT ''发布配置 SHA-256'' AFTER `published_version`');
CALL `forge_p1_add_column`('agent_info', 'prompt_template_id',
    '`prompt_template_id` BIGINT NULL COMMENT ''绑定 Prompt 模板ID'' AFTER `config_hash`');
CALL `forge_p1_add_column`('agent_info', 'prompt_binding_mode',
    '`prompt_binding_mode` VARCHAR(24) NOT NULL DEFAULT ''MANUAL'' COMMENT ''MANUAL/LOCKED/FOLLOW_STABLE'' AFTER `prompt_template_id`');
CALL `forge_p1_add_column`('agent_info', 'prompt_version_id',
    '`prompt_version_id` BIGINT NULL COMMENT ''LOCKED 模式锁定的模板版本ID'' AFTER `prompt_binding_mode`');
CALL `forge_p1_add_column`('agent_info', 'prompt_variables',
    '`prompt_variables` TEXT NULL COMMENT ''模板变量值 JSON'' AFTER `prompt_version_id`');

DROP PROCEDURE IF EXISTS `forge_p1_add_column`;

-- ---------------------------------------------------------------------
-- B1: Agent 定义版本快照
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agent_version`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`       BIGINT       NOT NULL COMMENT 'AgentID',
    `workspace_id`   BIGINT       NULL COMMENT '工作空间ID',
    `version_no`     INT          NOT NULL COMMENT '版本号(按 Agent 递增)',
    `snapshot_json`  MEDIUMTEXT   NOT NULL COMMENT '完整配置快照 JSON',
    `config_hash`    VARCHAR(64)  NULL COMMENT '快照 SHA-256',
    `remark`         VARCHAR(500) NULL COMMENT '版本说明',
    `source`         VARCHAR(24)  NOT NULL DEFAULT 'publish' COMMENT 'publish/rollback/manual',
    `create_by`      BIGINT       NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_version_no` (`agent_id`, `version_no`),
    KEY `idx_agent_version_time` (`agent_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 配置版本快照';

-- ---------------------------------------------------------------------
-- B2: Prompt 模板与版本
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agent_prompt_template`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `workspace_id`      BIGINT       NOT NULL,
    `name`              VARCHAR(100) NOT NULL,
    `description`       VARCHAR(500) NULL,
    `category`          VARCHAR(32)  NOT NULL DEFAULT 'system' COMMENT 'system/user/rag/tool/evaluation/workflow',
    `content`           TEXT         NOT NULL,
    `variable_schema`   TEXT         NULL COMMENT '强类型变量契约 JSON',
    `stable_version_id` BIGINT       NULL COMMENT '稳定版版本ID(FOLLOW_STABLE 指向)',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'draft',
    `create_by`         BIGINT       NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NULL,
    `del_flag`          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_ws_name` (`workspace_id`, `name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Prompt 模板';

CREATE TABLE IF NOT EXISTS `agent_prompt_version`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `template_id`     BIGINT      NOT NULL,
    `version_no`      INT         NOT NULL,
    `content`         TEXT        NOT NULL,
    `variable_schema` TEXT        NULL,
    `content_hash`    VARCHAR(64) NOT NULL COMMENT '渲染内容 SHA-256',
    `remark`          VARCHAR(500) NULL,
    `create_by`       BIGINT      NULL,
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_version_no` (`template_id`, `version_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Prompt 模板版本';

-- ---------------------------------------------------------------------
-- B3: 效果评测
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agent_eval_dataset`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `workspace_id` BIGINT       NOT NULL,
    `agent_id`     BIGINT       NULL COMMENT '建议绑定的 Agent(可空)',
    `name`         VARCHAR(100) NOT NULL,
    `description`  VARCHAR(500) NULL,
    `create_by`    BIGINT       NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `del_flag`     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_eval_dataset_ws` (`workspace_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 评测集';

CREATE TABLE IF NOT EXISTS `agent_eval_case`
(
    `id`                BIGINT   NOT NULL AUTO_INCREMENT,
    `dataset_id`        BIGINT   NOT NULL,
    `question`          TEXT     NOT NULL,
    `expected_keywords` VARCHAR(1000) NULL COMMENT '规则评分关键词(逗号分隔)',
    `reference_answer`  TEXT     NULL COMMENT '参考答案(Judge 用)',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_eval_case_dataset` (`dataset_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评测样本';

CREATE TABLE IF NOT EXISTS `agent_eval_run`
(
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `dataset_id`        BIGINT        NOT NULL,
    `agent_id`          BIGINT        NOT NULL,
    `workspace_id`      BIGINT        NULL,
    `status`            VARCHAR(16)   NOT NULL DEFAULT 'running' COMMENT 'running/success/failed',
    `total_cases`       INT           NOT NULL DEFAULT 0,
    `finished_cases`    INT           NOT NULL DEFAULT 0,
    `avg_score`         DECIMAL(6, 2) NULL,
    `tool_success_rate` DECIMAL(6, 2) NULL,
    `refusal_rate`      DECIMAL(6, 2) NULL,
    `p95_latency_ms`    BIGINT        NULL,
    `total_tokens`      INT           NULL,
    `judge_enabled`     CHAR(1)       NOT NULL DEFAULT '0',
    `error_message`     VARCHAR(1000) NULL,
    `create_by`         BIGINT        NULL,
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finish_time`       DATETIME      NULL,
    PRIMARY KEY (`id`),
    KEY `idx_eval_run_agent` (`agent_id`, `create_time`),
    KEY `idx_eval_run_dataset` (`dataset_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评测任务';

CREATE TABLE IF NOT EXISTS `agent_eval_case_result`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `run_id`        BIGINT        NOT NULL,
    `case_id`       BIGINT        NOT NULL,
    `question`      TEXT          NULL,
    `answer`        MEDIUMTEXT    NULL,
    `score`         DECIMAL(6, 2) NULL COMMENT '规则分',
    `judge_score`   DECIMAL(6, 2) NULL COMMENT 'LLM-as-Judge 分',
    `judge_reason`  VARCHAR(1000) NULL,
    `latency_ms`    BIGINT        NULL,
    `total_tokens`  INT           NULL,
    `error_message` VARCHAR(1000) NULL,
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_eval_result_run` (`run_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评测样本结果';

CREATE TABLE IF NOT EXISTS `agent_eval_baseline`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `agent_id`     BIGINT      NOT NULL,
    `dataset_id`   BIGINT      NOT NULL,
    `run_id`       BIGINT      NOT NULL,
    `metrics_json` TEXT        NOT NULL,
    `active`       CHAR(1)     NOT NULL DEFAULT '1',
    `create_by`    BIGINT      NULL,
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_eval_baseline_agent` (`agent_id`, `active`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '黄金评测基线';

-- ---------------------------------------------------------------------
-- B3: 发布门禁(策略缺省时用内置默认值,不会无条件放行)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `release_gate_policy`
(
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `workspace_id`          BIGINT        NULL COMMENT 'NULL = 平台默认策略',
    `resource_type`         VARCHAR(24)   NOT NULL DEFAULT 'agent',
    `enabled`               CHAR(1)       NOT NULL DEFAULT '1',
    `min_eval_score`        DECIMAL(6, 2) NOT NULL DEFAULT 60.00,
    `max_failure_rate`      DECIMAL(6, 2) NOT NULL DEFAULT 20.00 COMMENT '百分比',
    `max_p95_latency_ms`    BIGINT        NOT NULL DEFAULT 60000,
    `require_baseline`      CHAR(1)       NOT NULL DEFAULT '0',
    `max_metric_regression` DECIMAL(6, 2) NOT NULL DEFAULT 10.00 COMMENT '允许退化的百分点',
    `require_security_pass` CHAR(1)       NOT NULL DEFAULT '0',
    `require_cost_budget`   CHAR(1)       NOT NULL DEFAULT '0',
    `create_time`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gate_policy_ws` (`workspace_id`, `resource_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '发布门禁策略';

CREATE TABLE IF NOT EXISTS `release_gate_execution`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`      BIGINT       NOT NULL,
    `target_version` INT         NULL,
    `status`        VARCHAR(16)  NOT NULL COMMENT 'passed/blocked/preview',
    `checks_json`   TEXT         NULL,
    `reason`        VARCHAR(1000) NULL,
    `waived`        CHAR(1)      NOT NULL DEFAULT '0',
    `requested_by`  BIGINT       NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_gate_exec_agent` (`agent_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '门禁执行明细';

CREATE TABLE IF NOT EXISTS `release_gate_waiver`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`     BIGINT       NOT NULL,
    `reason`       VARCHAR(500) NOT NULL,
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
    `requested_by` BIGINT       NULL,
    `approved_by`  BIGINT       NULL COMMENT '审批人须与申请人不同',
    `expires_at`   DATETIME     NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_gate_waiver_agent` (`agent_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '门禁豁免申请';
