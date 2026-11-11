/** 消息反馈视图对象 */
export interface FeedbackVO {
  messageId: number | string;
  sessionId: number | string;
  agentId?: number | string;
  agentName?: string;
  userId?: number | string;
  userName?: string;
  /** like 赞 / dislike 踩 */
  feedback: string;
  feedbackTags?: string;
  feedbackComment?: string;
  feedbackTime?: string;
  /** 助手消息快照 */
  msgSnapshot?: string;
  msgTime?: string;
}

/** 反馈查询参数 */
export interface FeedbackQuery extends PageQuery {
  agentId?: number | string;
  feedback?: string;
  beginTime?: string;
  endTime?: string;
}
