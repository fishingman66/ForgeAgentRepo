/** 会话视图对象 */
export interface SessionVO {
  id: number | string;
  agentId: number | string;
  agentName?: string;
  title: string;
  messageCount?: number;
  /** 是否置顶(0否 1是) */
  pinned?: string;
  lastMsgTime?: string;
  createTime?: string;
}

/** 会话附件摘要(upload 返回值与消息 attachments 数组元素) */
export interface ChatAttachment {
  id: string | number;
  name: string;
  mediaType: string;
  /** 附件类型:image图片 / file文件 */
  kind?: 'image' | 'file';
  /** 文件扩展名 */
  fileExt?: string;
  size: number;
  thumb?: string;
  /** 解析状态:0解析中 1可用 2失败 */
  parseStatus?: string;
  chunkCount?: number;
  tokenCount?: number;
  pageCount?: number;
  summary?: string;
  failReason?: string;
}

/** 会话查询参数 */
export interface SessionQuery extends PageQuery {
  keyword?: string;
}

/** 消息视图对象 */
export interface MessageVO {
  id: number | string;
  sessionId: number | string;
  role: 'user' | 'assistant';
  content: string;
  extJson?: string;
  /** 用户反馈:like 点赞 / dislike 点踩 / 空为未反馈 */
  feedback?: 'like' | 'dislike' | '';
  /** 反馈备注 */
  feedbackComment?: string;
  /** 反馈标签(逗号分隔) */
  feedbackTags?: string;
  /** 反馈时间 */
  feedbackTime?: string;
  /** 引用溯源:知识库召回命中摘要 JSON 数组 */
  citations?: string;
  /** 图片附件 JSON 数组字符串 */
  attachments?: string;
  createTime?: string;
}

/** 发送消息参数 */
export interface ChatSendForm {
  sessionId?: number | string;
  agentId?: number | string;
  content: string;
  /** 附件 ID(图片/文件,字符串数组,避免大整数精度丢失) */
  attachmentIds?: Array<string | number>;
  /** UX-01 调试会话 ID(复用临时会话时传) */
  debugSessionId?: number | string;
  /** UX-01 调试草稿(未保存的表单值,覆盖 Agent 配置) */
  agentDraft?: AgentDraftForm;
}

/** UX-01 调试草稿(与后端 AgentDraftBo 对应) */
export interface AgentDraftForm {
  agentId: number | string;
  systemPrompt?: string;
  modelId?: number | string;
  /** 逗号分隔 ID 串 */
  toolIdsStr?: string;
  knowledgeIdsStr?: string;
  mcpIdsStr?: string;
}

/** 发送消息结果 */
export interface ChatSendResult {
  sessionId: number | string;
  userMessage: MessageVO;
  assistantMessage: MessageVO;
}

/** 会话 Token 用量实时反馈(UX-03) */
export interface SessionUsageBar {
  sessionId: number | string;
  /** 本会话累计 Token(输入+输出) */
  totalTokens: number;
  /** 累计输入 Token */
  promptTokens: number;
  /** 累计输出 Token */
  completionTokens: number;
  /** 最近一轮消耗 Token */
  lastRoundTokens: number;
  /** 模型上下文窗口(未配置为 null,进度条不渲染) */
  contextWindow?: number | null;
  /** 上下文已占用 tokens */
  contextUsed?: number | null;
  /** 上下文剩余百分比 0-100 */
  contextRemainingPercent?: number | null;
  /** 累计成本估算(模型未配置定价为 null) */
  costEstimate?: number | null;
  /** 定价币种 */
  currency?: string;
}
