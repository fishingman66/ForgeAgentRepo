/** Token 用量报表 */
export interface UsageReportVO {
  totalTokens: number;
  promptTokens: number;
  completionTokens: number;
  callCount: number;
  /** 估算数据占比(0-1) */
  estimatedRatio?: number;
  dataEndTime?: string;
  dailyTrend: DailyItem[];
  agentRank: RankItem[];
  modelRank: RankItem[];
}

export interface DailyItem {
  statDate: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  callCount: number;
}

export interface RankItem {
  name: string;
  totalTokens: number;
  callCount: number;
  /** 占比(0-1) */
  ratio?: number;
}

/** 用量明细 */
export interface TokenUsageVO {
  id: string;
  workspaceId?: string;
  agentId?: string;
  agentName?: string;
  sessionId?: string;
  roundRequestId?: string;
  userId?: string;
  userName?: string;
  /** chat / apikey */
  source: string;
  apiKeyId?: string;
  modelId?: string;
  modelName?: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** 0否 1是(估算) */
  estimated?: string;
  /** 0成功 1失败 */
  success?: string;
  callTime?: string;
  createTime?: string;
}

/** 会话内消耗汇总 */
export interface SessionUsageVO {
  sessionId: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  callCount: number;
  tokenBudget?: number;
  budgetUsage?: number;
}

/** 用量查询参数 */
export interface UsageQuery extends PageQuery {
  workspaceId?: string;
  deptId?: string | number;
  agentId?: string;
  modelName?: string;
  userId?: string;
  source?: string;
  estimated?: string;
  sessionId?: string;
  beginTime?: string;
  endTime?: string;
}
