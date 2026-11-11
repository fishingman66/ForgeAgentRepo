-- ============================================================
-- UX-01: Agent 配置页内嵌迷你调试窗(临时会话标记 + 索引)
-- 调试会话 token 计入用户配额,但不进正式会话列表、30 分钟无活动自动清理
-- ============================================================

ALTER TABLE agent_session
    ADD COLUMN temp TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否调试临时会话(0否 1是)' AFTER pinned;

CREATE INDEX idx_agent_session_temp ON agent_session(temp, create_time);
