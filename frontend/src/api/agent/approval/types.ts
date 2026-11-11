/** 工具审批单视图对象 */
export interface ApprovalVO {
  id: string;
  sessionId: number | string;
  agentId: number | string;
  roundId?: string;
  toolName: string;
  /** 工具来源(mcp/platform-http) */
  toolSource: string;
  serverName?: string;
  /** 调用参数(JSON 字符串) */
  callArgs?: string;
  /** 状态(pending/approved/rejected/expired/cancelled) */
  status: string;
  /** G1:审批截止时间(工作流审批单过期清扫依据) */
  expiredAt?: string;
  resultNote?: string;
  approveBy?: number | string;
  approveTime?: string;
  createTime?: string;
}

/** 审批单查询参数 */
export interface ApprovalQuery extends PageQuery {
  status?: string;
  keyword?: string;
  /** G1:来源筛选(mcp/tool/workflow) */
  toolSource?: string;
}
