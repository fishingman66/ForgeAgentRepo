-- ----------------------------
-- 第五步:SSE 流式输出 + 长期记忆 + 工作流人工审批节点
-- ----------------------------

-- 长期记忆开关(0关1开,开启后对话自动注入记忆并异步提取新记忆)
ALTER TABLE `agent_info`
    ADD COLUMN `memory_enabled` CHAR(1) NOT NULL DEFAULT '0' COMMENT '长期记忆开关(0关1开)' AFTER `team_ids`;

-- 工作流审批单无关联会话,放开 agent_approval.session_id 非空约束
ALTER TABLE `agent_approval`
    MODIFY COLUMN `session_id` BIGINT NULL COMMENT '会话ID(工作流审批单为空)';

-- Agent 长期记忆表:跨会话沉淀的用户偏好/事实,按 Agent+用户 维度存储
CREATE TABLE `agent_memory` (
    `id`                BIGINT       NOT NULL                   COMMENT '主键',
    `agent_id`          BIGINT       NOT NULL                   COMMENT 'AgentID',
    `workspace_id`      BIGINT       NULL                       COMMENT '工作空间ID',
    `user_id`           BIGINT       NULL                       COMMENT '用户ID',
    `content`           VARCHAR(1000) NOT NULL                  COMMENT '记忆内容',
    `source_session_id` BIGINT       NULL                       COMMENT '来源会话ID',
    `create_dept`       BIGINT       NULL                       COMMENT '创建部门',
    `create_by`         BIGINT       NULL                       COMMENT '创建者',
    `create_time`       DATETIME     NULL                       COMMENT '创建时间',
    `update_by`         BIGINT       NULL                       COMMENT '更新者',
    `update_time`       DATETIME     NULL                       COMMENT '更新时间',
    `del_flag`          BIGINT       NOT NULL DEFAULT 0         COMMENT '删除标志(0存在 其他删除)',
    PRIMARY KEY (`id`),
    KEY `idx_agent_user` (`agent_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆';

-- 工作流运行实例表:人工审批节点暂停时持久化运行状态,支持审批后恢复续跑
CREATE TABLE `agent_workflow_run` (
    `id`             BIGINT        NOT NULL                 COMMENT '主键',
    `agent_id`       BIGINT        NOT NULL                 COMMENT 'AgentID',
    `workspace_id`   BIGINT        NULL                     COMMENT '工作空间ID',
    `workflow_json`  MEDIUMTEXT    NULL                     COMMENT '工作流JSON快照',
    `inputs`         TEXT          NULL                     COMMENT '试运行输入',
    `vars_json`      MEDIUMTEXT    NULL                     COMMENT '变量上下文快照(JSON)',
    `trace_json`     MEDIUMTEXT    NULL                     COMMENT '节点执行轨迹快照(JSON)',
    `completed_json` TEXT          NULL                     COMMENT '已完成节点ID列表(JSON数组)',
    `skipped_json`   TEXT          NULL                     COMMENT '已跳过节点ID列表(JSON数组)',
    `status`         VARCHAR(32)   NOT NULL DEFAULT 'running' COMMENT '状态(pending_approval/success/fail)',
    `pending_node`   VARCHAR(64)   NULL                     COMMENT '等待审批的节点ID',
    `approval_id`    BIGINT        NULL                     COMMENT '关联审批单ID',
    `user_id`        BIGINT        NULL                     COMMENT '发起人用户ID',
    `create_dept`    BIGINT        NULL                     COMMENT '创建部门',
    `create_by`      BIGINT        NULL                     COMMENT '创建者',
    `create_time`    DATETIME      NULL                     COMMENT '创建时间',
    `update_by`      BIGINT        NULL                     COMMENT '更新者',
    `update_time`    DATETIME      NULL                     COMMENT '更新时间',
    `del_flag`       BIGINT        NOT NULL DEFAULT 0       COMMENT '删除标志(0存在 其他删除)',
    PRIMARY KEY (`id`),
    KEY `idx_agent_status` (`agent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流运行实例';

-- ----------------------------
-- 菜单:长期记忆管理
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030161, '长期记忆', 1761400000000030001, 11, 'memory', 'agent/memory/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:memory:list', 'star', null,
        null, 1, now(), 'Agent 长期记忆管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030162, '记忆查询', 1761400000000030161, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:memory:query', '#', null, null, 1, now(), ''),
(1761400000000030163, '记忆新增', 1761400000000030161, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:memory:add', '#', null, null, 1, now(), ''),
(1761400000000030164, '记忆删除', 1761400000000030161, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:memory:remove', '#', null, null, 1, now(), '');
