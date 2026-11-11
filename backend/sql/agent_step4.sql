-- ----------------------------
-- AgentScope 集成第四步:Agent Team + 结构化输出
-- agent_info 扩展:结构化输出 Schema / 团队协作模式 / 协作成员
-- ----------------------------

-- 结构化输出 JSON Schema(空为关闭,配置后回复为受约束 JSON)
ALTER TABLE `agent_info`
    ADD COLUMN `output_schema` TEXT NULL COMMENT '结构化输出JSON Schema(空为关闭)' AFTER `token_budget`;

-- 团队协作模式(none 不启用 / sequential 顺序接力:成员先行处理,主Agent最后综合)
ALTER TABLE `agent_info`
    ADD COLUMN `team_mode` VARCHAR(20) NOT NULL DEFAULT 'none' COMMENT '团队协作模式(none/sequential)' AFTER `output_schema`;

-- 协作成员AgentID列表(逗号分隔,仅 team_mode=sequential 时生效;成员沿用各自模型与提示词,不带工具)
ALTER TABLE `agent_info`
    ADD COLUMN `team_ids` VARCHAR(2000) NULL COMMENT '协作成员AgentID列表(逗号分隔)' AFTER `team_mode`;
