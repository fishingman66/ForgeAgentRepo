-- =====================================================================
-- P0: 全链路 Run / Trace 与 SSE 运行时可靠性
-- 目标库: forge-vue
-- 幂等: 可重复执行;建表用 IF NOT EXISTS,不修改既有表
-- =====================================================================
SET NAMES utf8mb4;

-- 一次对话轮次运行主记录(run_id 复用 AgentChatServiceImpl 生成的 roundId)
CREATE TABLE IF NOT EXISTS agent_run
(
    run_id                 varchar(64)   NOT NULL COMMENT '运行ID(轮次 roundId,UUID)',
    workspace_id           bigint        NULL COMMENT '所属工作空间',
    agent_id               bigint        NULL COMMENT 'Agent ID',
    session_id             bigint        NULL COMMENT '会话 ID',
    user_id                bigint        NULL COMMENT '发起用户 ID(重放鉴权用)',
    model_name             varchar(128)  NULL COMMENT '执行时模型标识',
    run_type               varchar(24)   NOT NULL DEFAULT 'chat' COMMENT 'chat/debug/regenerate/blocking',
    status                 varchar(16)   NOT NULL DEFAULT 'running' COMMENT 'running/success/failed/cancelled',
    prompt_tokens          int           NULL DEFAULT 0,
    completion_tokens      int           NULL DEFAULT 0,
    total_tokens           int           NULL DEFAULT 0,
    latency_ms             bigint        NULL COMMENT '本轮总耗时',
    first_token_latency_ms bigint        NULL COMMENT '首个增量片段延迟',
    error_message          varchar(1000) NULL COMMENT '失败原因(脱敏后)',
    create_time            datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time            datetime      NULL,
    PRIMARY KEY (run_id),
    KEY idx_agent_run_session (session_id, create_time),
    KEY idx_agent_run_agent (agent_id, create_time),
    KEY idx_agent_run_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行主记录';

-- 运行步骤明细(上下文装配/检索/预算/LLM/工具/团队/收尾)
CREATE TABLE IF NOT EXISTS agent_run_step
(
    id           bigint       NOT NULL AUTO_INCREMENT,
    run_id       varchar(64)  NOT NULL COMMENT '运行 ID',
    step_no      int          NOT NULL DEFAULT 0 COMMENT '步骤序号',
    step_type    varchar(32)  NOT NULL COMMENT 'ROUTER/CONTEXT/RETRIEVAL/MEMORY/SEMANTIC/BUDGET/LLM/TEAM/WORKFLOW/ERROR(常量见 TraceStepType)',
    step_name    varchar(128) NULL COMMENT '步骤名称',
    status       varchar(16)  NOT NULL DEFAULT 'success' COMMENT 'success/failed/skipped',
    latency_ms   bigint       NULL COMMENT '步骤耗时',
    token_usage  int          NULL COMMENT '该步骤 Token 用量',
    payload      longtext     NULL COMMENT '阶段载荷 JSON(截断后)',
    create_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_run_step_run (run_id, step_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行步骤明细';
