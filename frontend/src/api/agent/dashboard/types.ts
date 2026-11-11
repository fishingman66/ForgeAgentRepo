/** 运营看板概览 */
export interface DashboardOverviewVO {
  /** 视角: platform 平台 / workspace 空间 */
  scope: 'platform' | 'workspace' | string;
  /** 数据截止时间 */
  dataEndTime?: string;
  /** KPI 指标区 */
  kpi?: DashboardKpi;
  /** Token 趋势 */
  trend?: DashboardTrendPoint[];
  /** Agent 用量排行 */
  agentRank?: DashboardRankItem[];
  /** 待办事项区 */
  todo?: DashboardTodo;
  /** 消息反馈区 */
  feedback?: DashboardFeedback;
}

/** KPI 指标区 */
export interface DashboardKpi {
  workspaceCount?: number | null;
  memberCount?: number | null;
  agentCount?: number | null;
  draftCount?: number | null;
  publishedCount?: number | null;
  disabledCount?: number | null;
  sessionCount?: number | null;
  messageCount?: number | null;
  totalTokens?: number | null;
  callCount?: number | null;
}

/** 趋势点 */
export interface DashboardTrendPoint {
  statDate: string;
  totalTokens: number;
  callCount: number;
}

/** 排行项 */
export interface DashboardRankItem {
  name: string;
  totalTokens: number;
  callCount: number;
  ratio: number;
}

/** 待办事项区 */
export interface DashboardTodo {
  reviewPendingCount?: number | null;
  approvalPendingCount?: number | null;
  workflowFailCount?: number | null;
  triggerFailCount?: number | null;
}

/** 消息反馈区(助手消息点赞/点踩) */
export interface DashboardFeedback {
  /** 累计点赞数 */
  likeCount?: number | null;
  /** 累计点踩数 */
  dislikeCount?: number | null;
  /** 今日点赞数 */
  likeCountToday?: number | null;
  /** 今日点踩数 */
  dislikeCountToday?: number | null;
  /** 满意度(%,无反馈时为 null) */
  satisfaction?: number | null;
}
