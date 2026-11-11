import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';
import { getToken } from '@/utils/auth';

/**
 * 智能客服域 API 汇总(S0 ~ C5)。
 *
 * 分段:C1 坐席工作台(tickets/channels/seats) → C2 运营(csat/quality/knowledge/metrics)
 * → C4 合规与模板(audit/erase/templates) → C5 呼叫中心(calls/recording/seats/metric)。
 *
 * 扩展方向:新增页面 = 先在本文件加接口与 interface(字段名与后端 domain 驼峰一致),
 * 再在 views/customerservice/ 建同名 Vue;语音域字段以 CsCall 系列为准,
 * 新事件类型在前端 dispatchStreamEvent 与 Calls.vue 详情时间轴上按需分支。
 */

/** 坐席工单(C1):七态流转的主体,语音通话挂机后也会收敛到 RESOLVED。 */
export interface CsTicket {
  id: number;
  ticketNo: string;
  status: string;
  title: string;
  category: string;
  priority: string;
  handoffReason: string;
  assigneeId: number | null;
  conversationId: number;
  waitingSince: string | null;
  createTime: string | null;
}

/** 工单事件轨迹(C1):与七态一一对应,详情页时间轴按 createTime 升序渲染。 */
export interface CsTicketEvent {
  id: number;
  eventType: string;
  fromStatus: string | null;
  toStatus: string | null;
  actorType: string;
  note: string | null;
  createTime: string | null;
}

/** 接入渠道(C3):channelSecret 仅保存用,列表接口不回传明文。 */
export interface CsChannel {
  id?: number;
  channelCode: string;
  channelType: string;
  agentId: number;
  ownerUserId: number;
  knowledgeIds: string;
  fallbackReply: string;
  handoffPolicy: string;
  sessionMode: string;
  channelSecret: string;
  corsAllowlist: string;
  rateLimit: number;
  status: string;
}

export const listTickets = (status?: string): AxiosPromise<CsTicket[]> =>
  request({ url: '/agent/customerservice/tickets', method: 'get', params: { status } });

export const getTicket = (id: number): AxiosPromise<{ ticket: CsTicket | null; events: CsTicketEvent[] }> =>
  request({ url: `/agent/customerservice/tickets/${id}`, method: 'get' });

export const claimTicket = (id: number) =>
  request({ url: `/agent/customerservice/tickets/${id}/claim`, method: 'post' });

export const replyTicket = (id: number, content: string) =>
  request({ url: `/agent/customerservice/tickets/${id}/reply`, method: 'post', data: { content } });

export const transitionTicket = (id: number, action: string, note?: string) =>
  request({ url: `/agent/customerservice/tickets/${id}/${action}`, method: 'post', data: { note } });

/** 转派(P0):传 assigneeId 转给指定坐席,不传则退回工单池由 ACD 重新分配。 */
export const transferTicket = (id: number, assigneeId?: number, note?: string) =>
  request({
    url: `/agent/customerservice/tickets/${id}/transfer`,
    method: 'post',
    data: { assigneeId, note }
  });

export const assignQueue = () =>
  request({ url: '/agent/customerservice/queue/assign', method: 'post' });

export const listChannels = (): AxiosPromise<CsChannel[]> =>
  request({ url: '/agent/customerservice/channels', method: 'get' });

export const saveChannel = (data: CsChannel) =>
  request({ url: '/agent/customerservice/channels', method: 'post', data });

export const listSeats = () =>
  request({ url: '/agent/customerservice/seats', method: 'get' });

/* ---------------- C2 运营 ---------------- */

export interface CsCsat {
  id: number;
  conversationId: number;
  score: number | null;
  comment: string;
  tags: string;
  invitedAt: string | null;
  submittedAt: string | null;
}

export interface CsQualityRule {
  id?: number;
  ruleType: string;
  pattern: string;
  scoreDelta: number;
  severity: string;
  enabled: string;
}

export interface CsQualityCheck {
  id: number;
  messageId: number;
  ticketId: number;
  score: number;
  hitRules: string;
  reviewStatus: string;
  createTime: string | null;
}

export interface CsKnowledgeAnswer {
  id?: number;
  question: string;
  answer: string;
  source: string;
  status: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}

export interface CsMetricDaily {
  statDate: string;
  botSessions: number;
  handoffCount: number;
  firstResponseP95Ms: number;
  resolveRate: number;
  handoffRate: number;
  csatAvg: number;
  csatResponseRate: number;
  slaPassRate: number;
}

export const listCsat = (): AxiosPromise<CsCsat[]> =>
  request({ url: '/agent/customerservice/csat', method: 'get' });

export const csatStats = (from?: string, to?: string): AxiosPromise<Record<string, number>> =>
  request({ url: '/agent/customerservice/csat/stats', method: 'get', params: { from, to } });

export const listQualityRules = (): AxiosPromise<CsQualityRule[]> =>
  request({ url: '/agent/customerservice/quality/rules', method: 'get' });

export const saveQualityRule = (data: CsQualityRule) =>
  request({ url: '/agent/customerservice/quality/rules', method: 'post', data });

export const listQualityChecks = (): AxiosPromise<CsQualityCheck[]> =>
  request({ url: '/agent/customerservice/quality/checks', method: 'get' });

export const reviewQuality = (id: number, status: string) =>
  request({ url: `/agent/customerservice/quality/checks/${id}/review`, method: 'post', params: { status } });

export const listAnswers = (status?: string): AxiosPromise<CsKnowledgeAnswer[]> =>
  request({ url: '/agent/customerservice/knowledge-answers', method: 'get', params: { status } });

export const saveAnswer = (data: CsKnowledgeAnswer) =>
  request({ url: '/agent/customerservice/knowledge-answers', method: 'post', data });

export const auditAnswer = (id: number, status: string, note?: string) =>
  request({ url: `/agent/customerservice/knowledge-answers/${id}/audit`, method: 'post', params: { status, note } });

export const fromZeroHit = (limit = 20) =>
  request({ url: '/agent/customerservice/knowledge-answers/from-zero-hit', method: 'post', params: { limit } });

export const expireAnswers = () =>
  request({ url: '/agent/customerservice/knowledge-answers/expire', method: 'post' });

export const listMetrics = (from?: string, to?: string): AxiosPromise<CsMetricDaily[]> =>
  request({ url: '/agent/customerservice/metrics', method: 'get', params: { from, to } });

export const rebuildMetrics = (statDate?: string) =>
  request({ url: '/agent/customerservice/metrics/rebuild', method: 'post', params: { statDate } });

export const listBadcases = () =>
  request({ url: '/agent/customerservice/badcases', method: 'get' });

export const submitFeedback = (messageId: number, feedback: string) =>
  request({ url: `/agent/customerservice/feedback/${messageId}`, method: 'post', params: { feedback } });

/* ---------------- C4 合规与模板 ---------------- */

export interface CsAuditLog {
  id: number;
  eventType: string;
  actorType: string;
  actorId: string;
  channelCode: string;
  detail: string;
  createTime: string | null;
}

export interface CsTemplate {
  code: string;
  name: string;
  handoffPolicy: string;
  fallbackReply: string;
  fallbackReplyEn: string;
  locale: string;
}

export const listAudit = (): AxiosPromise<CsAuditLog[]> =>
  request({ url: '/agent/customerservice/compliance/audit', method: 'get' });

export const eraseConversation = (cid: number) =>
  request({ url: `/agent/customerservice/compliance/erase/${cid}`, method: 'post' });

export const purgeExpired = () =>
  request({ url: '/agent/customerservice/compliance/purge', method: 'post' });

export const listTemplates = (): AxiosPromise<CsTemplate[]> =>
  request({ url: '/agent/customerservice/templates', method: 'get' });

export const applyTemplate = (code: string, channelCode: string) =>
  request({ url: `/agent/customerservice/templates/${code}/apply`, method: 'post', params: { channelCode } });

/* ---------------- C5 呼叫中心 ---------------- */

/**
 * 通话主记录:与后端 agent_cc_call 一行对应。
 * callerNumber/ani 在服务端已 PII 脱敏;recordingOssKey 是存储对象键而非云侧 URL。
 */
export interface CsCall {
  id: number;
  callId: string;
  mediaProvider: string;
  channelCode: string;
  conversationId: number | null;
  ticketId: number | null;
  direction: string;
  callerNumber: string | null;
  calleeNumber: string | null;
  status: string;
  workNo: string | null;
  ringAt: string | null;
  answerAt: string | null;
  hangupAt: string | null;
  durationSec: number | null;
  billSec: number | null;
  hangupCause: string | null;
  hangupBy: string | null;
  recordingOssKey: string | null;
  recordingId: string | null;
}

/** 通话事件轨迹:唯一键 (callId, eventSeq, eventType) 幂等;dead.* 前缀为死信。 */
export interface CsCallEvent {
  id: number;
  callId: number;
  eventSeq: number;
  eventType: string;
  fromStatus: string | null;
  toStatus: string | null;
  actorType: string;
  payload: string | null;
  createTime: string | null;
}

/** 转写分片:speaker 为 caller/bot/seat,文本已脱敏;质检与知识缺口的数据源。 */
export interface CsCallSegment {
  id: number;
  callId: number;
  speaker: string;
  text: string | null;
  startMs: number | null;
  endMs: number | null;
  asrConfidence: number | null;
  isFinal: string;
  createTime: string | null;
}

/** 电话坐席就绪态:按 AICC 工号维护;seatId/userId 在坐席绑定关系落地后回填。 */
export interface CsCallSeatState {
  id: number;
  workNo: string;
  readyState: string;
  pauseReason: string | null;
  loginAt: string | null;
  updateTime: string | null;
}

/** 通话日指标:挂机触发按 workspace+自然日全量重算;字段与 agent_cc_metric_daily 一致。 */
export interface CsCallMetric {
  statDate: string;
  callCount: number;
  answeredCount: number;
  answerRate: number;
  avgDurationSec: number;
  botResolveRate: number;
  recordingCoverage: number;
}

export const listCalls = (status?: string, direction?: string): AxiosPromise<CsCall[]> =>
  request({ url: '/agent/customerservice/calls', method: 'get', params: { status, direction } });

export const getCallDetail = (
  id: number
): AxiosPromise<{ call: CsCall; events: CsCallEvent[]; segments: CsCallSegment[] }> =>
  request({ url: `/agent/customerservice/calls/${id}`, method: 'get' });

/** 录音取回:返回后端短期签名 URL(oss presign / local-fs HMAC 下载),过期后需重新取回。 */
export const getCallRecording = (id: number): AxiosPromise<{ url: string; ossKey: string; expireSeconds: number }> =>
  request({ url: `/agent/customerservice/calls/${id}/recording`, method: 'get' });

export const listCallSeats = (): AxiosPromise<CsCallSeatState[]> =>
  request({ url: '/agent/customerservice/calls/call-seats', method: 'get' });

export const callStats = (): AxiosPromise<CsCallMetric> =>
  request({ url: '/agent/customerservice/calls/stats', method: 'get' });

/* ---------------- A1 坐席辅助 ---------------- */

/**
 * 坐席话术推荐:items 为候选 JSON 快照字符串(faq/kb,上限 3 条),
 * payload 只增不改;adopted/adoptAction 为采纳埋点,效果度量数据源。
 */
export interface CsSeatSuggestion {
  id: number;
  conversationId: number;
  ticketId: number | null;
  intent: string | null;
  /** 意图名称(A3):仅事件信封下发,历史接口只有 intent 编码。 */
  intentName?: string | null;
  items: string;
  actions: string;
  /** 触发来源(A2):text 文本消息 / voice 语音转写;历史行为 null。 */
  source?: string | null;
  /** 语音来源的通话主键(A2),文本为空。 */
  callId?: number | null;
  topScore: number | null;
  adopted: string;
  adoptAction: string | null;
  createTime: string | null;
}

export const listSuggestions = (conversationId: number): AxiosPromise<CsSeatSuggestion[]> =>
  request({ url: '/agent/customerservice/suggestions', method: 'get', params: { conversationId } });

/** 采纳反馈:send=采纳填入 / copy=复制 / ignore=忽略;从未采纳状态变更一次。 */
export const feedbackSuggestion = (id: number, action: string) =>
  request({ url: `/agent/customerservice/suggestions/${id}/feedback`, method: 'post', params: { action } });

/**
 * 建议动作执行(A4):code ∈ REPLY / LINK / RESOLVE,只执行该推荐 actions 快照里的动作。
 * 调用前必须由坐席二次确认(前端弹窗),服务端不提供自动执行入口。
 */
export const executeSuggestionAction = (id: number, code: string) =>
  request({ url: `/agent/customerservice/suggestions/${id}/actions/${code}`, method: 'post' });

/**
 * 会话事件实时流(游标轮询重放):与访客侧同一 RunEventBuffer 通道,
 * after 传上次最大序号,断线续传;事件含 cs_seat_suggest / call.transcript 等。
 */
export interface CsConversationEvent {
  id: number;
  event: string;
  data: Record<string, unknown>;
}

export const listConversationEvents = (
  conversationId: number,
  after: number
): AxiosPromise<CsConversationEvent[]> =>
  request({
    url: `/agent/customerservice/conversations/${conversationId}/events`,
    method: 'get',
    params: { after }
  });

/* ---------------- A3 意图体系 ---------------- */

/**
 * 意图标签:workspaceId 为空表示平台级(对所有空间生效、只读)。
 * intentCode 是落在推荐记录上的稳定键,运营按它聚合意图分布;
 * keywords 逗号分隔(兼容中文逗号/分号/顿号),ruleType=REGEX 时整段视为一条正则。
 */
export interface CsIntent {
  id?: number;
  workspaceId?: number | null;
  intentCode: string;
  intentName: string;
  keywords: string;
  ruleType?: string;
  kbIds?: string | null;
  priority?: number;
  enabled?: string;
  createTime?: string | null;
  updateTime?: string | null;
}

export const listIntents = (): AxiosPromise<CsIntent[]> =>
  request({ url: '/agent/customerservice/intents', method: 'get' });

/** 新增或编辑;平台级意图后端拒绝写入。 */
export const saveIntent = (data: CsIntent): AxiosPromise<CsIntent> =>
  request({ url: '/agent/customerservice/intents', method: 'post', data });

export const deleteIntent = (id: number): AxiosPromise<boolean> =>
  request({ url: `/agent/customerservice/intents/${id}`, method: 'delete' });

/* ---------------- P0 敏感词 ---------------- */

/** 敏感词:workspaceId 为空=平台级(只读);severity 决定 BLOCK 拦截 / MASK 打码 / WARN 仅告警。 */
export interface CsSensitiveWord {
  id?: number;
  workspaceId?: number | null;
  word: string;
  severity?: string;
  remark?: string | null;
  enabled?: string;
  createTime?: string | null;
}

export const listSensitiveWords = (): AxiosPromise<CsSensitiveWord[]> =>
  request({ url: '/agent/customerservice/sensitive-words', method: 'get' });

export const saveSensitiveWord = (data: CsSensitiveWord): AxiosPromise<CsSensitiveWord> =>
  request({ url: '/agent/customerservice/sensitive-words', method: 'post', data });

export const deleteSensitiveWord = (id: number): AxiosPromise<boolean> =>
  request({ url: `/agent/customerservice/sensitive-words/${id}`, method: 'delete' });

/* ---------------- P0 交接摘要 ---------------- */

/** 转人工后生成的结构化摘要:接手工单先看它,再看消息。 */
export interface CsHandoffSummary {
  oneLineSummary?: string;
  userIntent?: string | null;
  emotion?: string;
  triedSolutions?: string[];
  pendingIssues?: string[];
  suggestedNextStep?: string;
  suggestedReply?: string | null;
  turns?: number;
  degraded?: boolean;
}

export const getHandoffSummary = (ticketId: number): AxiosPromise<CsHandoffSummary> =>
  request({ url: '/agent/customerservice/summaries', method: 'get', params: { ticketId } });

/* ---------------- P1 语义缓存 / 知识缺口 / 改进单 ---------------- */

export interface CsSemanticCacheRow {
  id: number;
  channelCode: string;
  question: string;
  answer: string;
  intent: string | null;
  hits: number;
  updateTime: string | null;
}

export const listSemanticCache = (channelCode: string, limit = 50): AxiosPromise<CsSemanticCacheRow[]> =>
  request({
    url: '/agent/customerservice/semantic-cache',
    method: 'get',
    params: { channelCode, limit }
  });

export const clearSemanticCache = (channelCode: string) =>
  request({ url: '/agent/customerservice/semantic-cache', method: 'delete', params: { channelCode } });

export interface CsKnowledgeGapRow {
  id: number;
  question: string;
  category: string | null;
  priority: string;
  hitCount: number;
  status: string;
  lastHitAt: string | null;
}

export const listKnowledgeGaps = (status = 'OPEN', limit = 50): AxiosPromise<CsKnowledgeGapRow[]> =>
  request({
    url: '/agent/customerservice/knowledge-gaps',
    method: 'get',
    params: { status, limit }
  });

/** 一键把缺口转成待审答案（问题即缺口原文，答案待运营补）。 */
export const createGapAnswer = (id: number) =>
  request({ url: `/agent/customerservice/knowledge-gaps/${id}/create-answer`, method: 'post' });

export const ignoreGap = (id: number) =>
  request({ url: `/agent/customerservice/knowledge-gaps/${id}/ignore`, method: 'post' });

export interface CsBadcaseRow {
  id: number;
  source: string;
  question: string | null;
  answer: string | null;
  status: string;
  exported: string;
  createTime: string | null;
}

export const listImprovements = (status = 'PENDING', limit = 50): AxiosPromise<CsBadcaseRow[]> =>
  request({
    url: '/agent/customerservice/improvements',
    method: 'get',
    params: { status, limit }
  });

export const adoptImprovementKb = (id: number) =>
  request({ url: `/agent/customerservice/improvements/${id}/adopt-kb`, method: 'post' });

export const adoptImprovementEval = (id: number) =>
  request({ url: `/agent/customerservice/improvements/${id}/adopt-eval`, method: 'post' });

export const ignoreImprovement = (id: number, note?: string) =>
  request({
    url: `/agent/customerservice/improvements/${id}/ignore`,
    method: 'post',
    data: { note }
  });

/** 导出已标记的评测样本，运营导入平台评测集（客服域不自建评测引擎）。 */
export const exportEvalCases = (limit = 200): AxiosPromise<Record<string, unknown>[]> =>
  request({
    url: '/agent/customerservice/improvements/export-eval',
    method: 'get',
    params: { limit }
  });

/* ---------------- P2 成本与配额 ---------------- */

export interface CsCostDailyRow {
  statDate: string;
  conversationCount: number;
  callCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  estimatedCount: number;
  costAmount: number | null;
  resolvedCount: number;
}

export interface CsCostOverview {
  days: CsCostDailyRow[];
  total: {
    totalTokens: number;
    callCount: number;
    conversationCount: number;
    resolvedCount: number;
    tokensPerConversation: number;
    tokensPerResolved: number;
    costAmount: number | null;
    amountAvailable: boolean;
    currency: string;
  };
  priceNote: string;
}

export const assistCost = (days = 7): AxiosPromise<CsCostOverview> =>
  request({ url: '/agent/customerservice/assist/cost', method: 'get', params: { days } });

export const rebuildAssistCost = () =>
  request({ url: '/agent/customerservice/assist/cost/rebuild', method: 'post' });

export interface CsQuotaView {
  enabled: boolean;
  action: string;
  level: string;
  used: number;
  limit: number;
}

export const assistQuota = (): AxiosPromise<CsQuotaView> =>
  request({ url: '/agent/customerservice/assist/quota', method: 'get' });

/* ---------------- P2 收尾:附件与死信 ---------------- */

/** 会话附件(精简):不带宽高缩略图，避免列表响应过大。 */
export interface CsAttachmentBrief {
  id: number;
  name: string;
  mediaType: string;
  kind: string;
  fileExt: string;
  fileSize: number;
  parseStatus: string;
  summary: string | null;
}

export const listConversationAttachments = (conversationId: number): AxiosPromise<CsAttachmentBrief[]> =>
  request({
    url: `/agent/customerservice/conversations/${conversationId}/attachments`,
    method: 'get'
  });

/** 单个附件详情：图片带 thumb（data URI），文档带解析摘要。 */
export const getConversationAttachment = (conversationId: number, id: number) =>
  request({
    url: `/agent/customerservice/conversations/${conversationId}/attachments/${id}`,
    method: 'get'
  });

export interface CsDeadLetterRow {
  id: number;
  eventType: string;
  conversationId: number | null;
  ticketId: number | null;
  error: string | null;
  status: string;
  retryCount: number;
  createTime: string | null;
}

export const listDeadLetters = (status = 'PENDING', limit = 50): AxiosPromise<CsDeadLetterRow[]> =>
  request({
    url: '/agent/customerservice/dead-letters',
    method: 'get',
    params: { status, limit }
  });

/** 重投：摘要类可自动重投，附件类会返回“不可重投”。 */
export const replayDeadLetter = (id: number) =>
  request({ url: `/agent/customerservice/dead-letters/${id}/replay`, method: 'post' });

export const ignoreDeadLetter = (id: number) =>
  request({ url: `/agent/customerservice/dead-letters/${id}/ignore`, method: 'post' });

/* ---------------- P3 多 Agent 路由 ---------------- */

/** 路由：意图 → 专家 Agent。channelCode 为空表示对该空间所有渠道生效。 */
export interface CsAgentRouteRow {
  id: number;
  workspaceId?: number;
  channelCode: string | null;
  intentCode: string;
  agentId: number;
  priority: number;
  enabled: string;
}

export const listAgentRoutes = (channelCode?: string): AxiosPromise<CsAgentRouteRow[]> =>
  request({
    url: '/agent/customerservice/agent-routes',
    method: 'get',
    params: { channelCode }
  });

export const saveAgentRoute = (data: Partial<CsAgentRouteRow>) =>
  request({ url: '/agent/customerservice/agent-routes', method: 'post', data });

export const deleteAgentRoute = (id: number) =>
  request({ url: `/agent/customerservice/agent-routes/${id}`, method: 'delete' });

/* ---------------- P3 坐席实时事件流 ---------------- */

/**
 * 坐席会话事件流(P3):SSE 长连接替代 2 秒轮询，带 after 游标断线续传。
 *
 * <p>用 fetch + ReadableStream 而不是 EventSource——后者带不了 Authorization 头，
 * 只能把 token 放进 URL，会进访问日志。失败由调用方决定重连或降级为轮询。</p>
 */
export const streamConversationEvents = async (
  conversationId: number,
  after: number,
  onEvent: (event: string, data: string, seq: number) => void,
  signal?: AbortSignal
): Promise<void> => {
  const base = import.meta.env.VITE_APP_BASE_API || '';
  const resp = await fetch(
    `${base}/agent/customerservice/conversations/${conversationId}/stream?after=${after}`,
    {
      headers: {
        Authorization: 'Bearer ' + getToken(),
        clientid: import.meta.env.VITE_APP_CLIENT_ID
      },
      signal
    }
  );
  if (!resp.ok || !resp.body) {
    throw new Error('stream failed: ' + resp.status);
  }
  const reader = resp.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventName = '';
  let dataLines: string[] = [];
  let seq = 0;
  const flush = () => {
    if (eventName || dataLines.length) {
      onEvent(eventName, dataLines.join('\n'), seq);
    }
    eventName = '';
    dataLines = [];
    seq = 0;
  };
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf('\n')) > -1) {
      let line = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 1);
      if (line.endsWith('\r')) {
        line = line.slice(0, -1);
      }
      if (line === '') {
        flush();
      } else if (line.startsWith(':')) {
        // 心跳注释行，不派发
        continue;
      } else if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('id:')) {
        seq = Number(line.slice(3).trim()) || 0;
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).startsWith(' ') ? line.slice(6) : line.slice(5));
      }
    }
  }
};

/* ---------------- A5 坐席辅助度量 ---------------- */

/** 日指标:采纳率按「推荐条数」算(采纳条数 / 推荐条数),动作次数单列。 */
export interface CsAssistMetricDaily {
  id: number;
  statDate: string;
  suggestionCount: number;
  adoptedCount: number;
  actionCount: number;
  intentCount: number;
  recycledCount: number;
}

export interface CsAssistTotal {
  suggestionCount: number;
  adoptedCount: number;
  adoptRate: number;
  actionCount: number;
  intentCount: number;
  intentRate: number;
  recycledCount: number;
}

export interface CsAssistOverview {
  days: CsAssistMetricDaily[];
  total: CsAssistTotal;
  rateBasis: string;
}

export const assistMetrics = (days = 7): AxiosPromise<CsAssistOverview> =>
  request({ url: '/agent/customerservice/assist/metrics', method: 'get', params: { days } });

export const rebuildAssistMetrics = () =>
  request({ url: '/agent/customerservice/assist/metrics/rebuild', method: 'post' });

/** 零采纳回流:hours=0 表示不限时间窗;返回 scanned / recycled / created。 */
export const recycleZeroAdopt = (hours = 24, limit = 50) =>
  request({
    url: '/agent/customerservice/assist/recycle',
    method: 'post',
    params: { hours, limit }
  });

/** 低质推荐追溯:未采纳且带触发原文的推荐。 */
export const listAssistBadcases = (limit = 20): AxiosPromise<CsSeatSuggestion[]> =>
  request({ url: '/agent/customerservice/assist/badcases', method: 'get', params: { limit } });

/** 领域知识图谱三元组(P3/M3 后续)。 */
export interface CsKgRelationRow {
  id?: number;
  subject: string;
  relation: string;
  object: string;
  weight?: number;
  /** 同一实体对上存在相反说法,审核时人工判断删哪条。 */
  conflict?: boolean;
}

export interface CsKgNodeRow {
  key: string;
  name: string;
  type: string;
  salience: number;
}

export const listKgRelations = (limit = 100): AxiosPromise<CsKgRelationRow[]> =>
  request({ url: '/agent/customerservice/knowledge/graph/relations', method: 'get', params: { limit } });

/** 冲突盘点:同一实体对上说法相反的关系两两一组。 */
export const listKgConflicts = (limit = 100): AxiosPromise<CsKgRelationRow[][]> =>
  request({ url: '/agent/customerservice/knowledge/graph/conflicts', method: 'get', params: { limit } });

export const addKgRelation = (data: { subject: string; relation: string; object: string; evidenceRef?: number }) =>
  request({ url: '/agent/customerservice/knowledge/graph/relations', method: 'post', data });

export const deleteKgRelation = (id: number) =>
  request({ url: `/agent/customerservice/knowledge/graph/relations/${id}`, method: 'delete' });

export const listKgNodes = (limit = 50): AxiosPromise<CsKgNodeRow[]> =>
  request({ url: '/agent/customerservice/knowledge/graph/nodes', method: 'get', params: { limit } });

/** 检索扩展效果日聚合(M4 后续):零命中率由前端按行计算并对比 */
export interface CsKgExpandMetricRow {
  statDate: string;
  totalSearches: number;
  expandedSearches: number;
  zeroHitTotal: number;
  zeroHitExpanded: number;
}

export const listKgExpandMetrics = (days = 7): AxiosPromise<CsKgExpandMetricRow[]> =>
  request({ url: '/agent/customerservice/knowledge/graph/expand-metrics', method: 'get', params: { days } });

