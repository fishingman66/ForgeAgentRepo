-- agent_* 表第二批高频查询索引(会话列表 / 消息游标分页 / 审计检索)
-- 执行: mysql -h127.0.0.1 -uroot -p forge-vue < agent_perf_indexes_p1.sql

-- 会话列表:按用户过滤 + 置顶优先 + 最近消息时间排序
ALTER TABLE agent_session ADD INDEX idx_user_pinned_last (user_id, pinned, last_msg_time);

-- 消息游标分页:WHERE session_id = ? AND id < ? ORDER BY id DESC LIMIT ?
ALTER TABLE agent_message ADD INDEX idx_session_id (session_id, id);

-- 审计日志:按空间 + 时间倒序检索
ALTER TABLE agent_audit_log ADD INDEX idx_ws_ctime (workspace_id, create_time);
