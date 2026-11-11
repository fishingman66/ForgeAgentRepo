/** 审计日志视图对象 */
export interface AuditLogVO {
  id: string;
  workspaceId?: string;
  workspaceName?: string;
  eventType: string;
  targetType?: string;
  targetId?: string;
  sourceChannel?: string;
  clientIp?: string;
  userAgent?: string;
  result?: string;
  severity?: string;
  traceId?: string;
  oldValue?: string;
  newValue?: string;
  detail?: string;
  operatorId?: string;
  operatorName?: string;
  createTime?: string;
}

/** 审计日志查询参数 */
export interface AuditLogQuery extends PageQuery {
  workspaceId?: string;
  deptId?: string | number;
  eventType?: string;
  operatorName?: string;
  severity?: string;
  result?: string;
  sourceChannel?: string;
  beginTime?: string;
  endTime?: string;
}
