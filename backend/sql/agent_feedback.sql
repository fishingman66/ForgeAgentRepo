-- ----------------------------
-- 对话反馈与引用溯源
-- 1. agent_message 增加用户反馈列(点赞/点踩,供运营看板聚合)
-- 2. agent_message 增加引用溯源列(知识库召回命中摘要 JSON 数组)
-- ----------------------------

ALTER TABLE `agent_message`
    ADD COLUMN `feedback` VARCHAR(10) NULL COMMENT '用户反馈(like 点赞 / dislike 点踩)' AFTER `ext_json`,
    ADD COLUMN `citations` TEXT NULL COMMENT '引用溯源(知识库召回命中 JSON 数组)' AFTER `feedback`;
