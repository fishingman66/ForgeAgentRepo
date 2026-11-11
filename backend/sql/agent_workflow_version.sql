-- ----------------------------
-- 工作流版本管理 + 执行回放
-- ----------------------------

-- 工作流版本快照表:每次保存自动生成递增版本,支持版本预览与回滚
CREATE TABLE `agent_workflow_version` (
  `id`             BIGINT       NOT NULL                   COMMENT '主键',
  `agent_id`       BIGINT       NOT NULL                   COMMENT 'AgentID',
  `workspace_id`   BIGINT       NULL                       COMMENT '工作空间ID',
  `version`        INT          NOT NULL                   COMMENT '版本号(按Agent递增)',
  `workflow_json`  MEDIUMTEXT   NULL                       COMMENT '工作流JSON快照',
  `remark`         VARCHAR(500) NULL                       COMMENT '版本备注',
  `create_dept`    BIGINT       NULL                       COMMENT '创建部门',
  `create_by`      BIGINT       NULL                       COMMENT '创建者',
  `create_time`    DATETIME     NULL                       COMMENT '创建时间',
  `update_by`      BIGINT       NULL                       COMMENT '更新者',
  `update_time`    DATETIME     NULL                       COMMENT '更新时间',
  `del_flag`       BIGINT       NOT NULL DEFAULT 0         COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_version` (`agent_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流版本快照';
