-- ----------------------------
-- P0 快捷体验优化(2026-10-20)
-- 1. 会话置顶: agent_session.pinned
-- 2. Agent 推荐问题: agent_info.suggested_questions
-- ----------------------------

ALTER TABLE agent_session
    ADD COLUMN pinned char(1) DEFAULT '0' COMMENT '是否置顶(0否 1是)' AFTER message_count;

ALTER TABLE agent_info
    ADD COLUMN suggested_questions text COMMENT '推荐问题JSON数组,会话空态展示为可点击快捷提问' AFTER welcome_msg;
