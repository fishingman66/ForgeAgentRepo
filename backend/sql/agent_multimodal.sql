-- ----------------------------
-- 会话图片输入(多模态)(2026-10-20)
-- 1. agent_model 新增 support_vision(标记模型是否支持图片输入)
-- 2. agent_message 新增 attachments(消息图片附件 JSON 数组)
-- 3. 新增 agent_chat_attachment(会话图片附件表)
-- 执行前请确认当前库为业务库(如 forge-vue)
-- ----------------------------

-- 1. 模型是否支持图片输入
ALTER TABLE `agent_model`
    ADD COLUMN `support_vision` char(1) NOT NULL DEFAULT '0' COMMENT '支持图片输入(0否 1是)';

-- 2. 消息图片附件
ALTER TABLE `agent_message`
    ADD COLUMN `attachments` text NULL COMMENT '图片附件(JSON数组)';

-- 3. 会话图片附件表
create table if not exists agent_chat_attachment
(
    id           bigint       not null auto_increment comment '主键',
    stored_name  varchar(80)  not null comment '存储文件名(含扩展名)',
    original_name varchar(255) default null comment '用户原始文件名',
    media_type   varchar(50)  not null comment '媒体类型(image/png、image/jpeg)',
    file_size    bigint       not null default 0 comment '文件大小(字节)',
    thumb        longtext     null comment '缩略图(data URI)',
    workspace_id bigint       default null comment '所属工作空间ID',
    session_id   bigint       default null comment '发送后回填的会话ID',
    message_id   bigint       default null comment '发送后回填的消息ID',
    status       char(1)      not null default '0' comment '状态(0正常 1删除)',
    create_dept  bigint       null comment '创建部门',
    create_by    bigint       null comment '创建者',
    create_time  datetime     null comment '创建时间',
    update_by    bigint       null comment '更新者',
    update_time  datetime     null comment '更新时间',
    primary key (id),
    key idx_aca_ws (workspace_id)
) engine = innodb
  auto_increment = 1
  comment = '会话图片附件' collate = utf8mb4_unicode_ci;
