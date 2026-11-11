/** 触发器视图对象 */
export interface TriggerVO {
  id: number | string;
  name: string;
  workspaceId?: number | string;
  /** 触发类型:cron 定时 / webhook 事件 */
  triggerType: string;
  /** 目标类型:agent 对话 / workflow 工作流 */
  targetType: string;
  targetId: number | string;
  /** 目标名称(展示用) */
  targetName?: string;
  /** 输入模板(agent 为目标时为消息文本,workflow 为目标时为输入 JSON) */
  inputs?: string;
  /** Cron 表达式(cron 触发器) */
  cronExpr?: string;
  /** Webhook 密钥(服务端生成) */
  secret?: string;
  /** 是否启用:0 启用 / 1 停用 */
  enabled: string;
  lastRunTime?: string;
  /** 最近执行状态:success/fail */
  lastRunStatus?: string;
  runCount?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 触发器表单对象 */
export interface TriggerForm {
  id?: number | string;
  name: string;
  triggerType: string;
  targetType: string;
  targetId?: number | string;
  inputs?: string;
  cronExpr?: string;
  enabled?: string;
  remark?: string;
}

/** 触发器查询参数 */
export interface TriggerQuery extends PageQuery {
  keyword?: string;
  triggerTypeQuery?: string;
}

/** 触发器执行记录 */
export interface TriggerLogVO {
  id: number | string;
  triggerId: number | string;
  triggerName?: string;
  triggerType?: string;
  targetType?: string;
  targetId?: number | string;
  /** 调用来源:cron/webhook/manual */
  invokeSource?: string;
  /** 执行状态:success/fail */
  status?: string;
  message?: string;
  costMs?: number;
  runTime?: string;
  createTime?: string;
}

/** 触发器执行结果 */
export interface TriggerRunVO {
  triggerId: number | string;
  status?: string;
  message?: string;
  costMs?: number;
  targetType?: string;
  targetId?: number | string;
  targetName?: string;
  sessionId?: number | string;
  runId?: number | string;
  output?: string;
  runStatus?: string;
}
