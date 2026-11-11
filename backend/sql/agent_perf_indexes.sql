-- agent_* 表高频查询补索引:小改动大收益
-- 执行: mysql -h127.0.0.1 -uroot -p forge-vue < agent_perf_indexes.sql

-- 会话列表页高频:按 agent + create_time 排序取最近 N 条
ALTER TABLE agent_session ADD INDEX idx_agent_ctime (agent_id, create_time DESC);

-- 消息列表:按 session 拉全量消息并按时间排序
ALTER TABLE agent_message ADD INDEX idx_session_ctime (session_id, create_time ASC);

-- 多模态附件:按 session/message 查附件(会话详情页)
ALTER TABLE agent_chat_attachment ADD INDEX idx_message_ws (message_id, workspace_id);
ALTER TABLE agent_chat_attachment ADD INDEX idx_session (session_id);

-- 语义层路由:vocabulary 查询总是带 status='0' 过滤
ALTER TABLE agent_semantic_metric ADD INDEX idx_ds_status (datasource_id, status);
ALTER TABLE agent_semantic_dimension ADD INDEX idx_ds_status (datasource_id, status);
ALTER TABLE agent_semantic_value ADD INDEX idx_ds_status (datasource_id, status);
