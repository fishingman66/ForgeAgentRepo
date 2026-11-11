import { nextSseCursor } from '@/utils/governance';

import type { ChatSendResult } from './types';

/**
 * SSE 聊天流回调集合(体检 E3:接口与事件分发提取到独立模块,支撑 vitest 单测)。
 */
export interface StreamCallbacks {
  /** meta 事件:会话定位/创建完成,返回 sessionId */
  onMeta?: (sessionId: number | string) => void;
  /** delta 事件:增量文本片段 */
  onDelta?: (delta: string) => void;
  /** done 事件:完成,携带落库后的用户/助手消息 */
  onDone?: (result: ChatSendResult) => void;
  /** error 事件或网络层异常 */
  onError?: (message: string) => void;
  /** 连接关闭 */
  onClose?: () => void;
  /** run_id 事件:本轮运行 ID,用于断线续传与链路下钻 */
  onRunId?: (runId: string) => void;
  /** 断线后发起续传(attempt 从 1 开始) */
  onReconnect?: (attempt: number) => void;
  /** 团队接力:某一棒开始执行 */
  onTeamAgentStart?: (index: number, total: number, agentName: string, role: string) => void;
  /** 团队接力:某一棒增量文本 */
  onTeamAgentDelta?: (index: number, text: string) => void;
  /** 团队接力:某一棒执行完成 */
  onTeamAgentDone?: (index: number, content: string, totalTokens: number | null) => void;
  /** usage 事件:一轮对话落库前推送,携带本轮 tokens,前端据此刷新用量栏 */
  onUsage?: (sessionId: number | string, lastRoundTokens: number) => void;
  /** 工作流执行开始(体检 E1):wfRunId + 触发模式 */
  onWfStart?: (wfRunId: number | string, mode: string) => void;
  /** 工作流节点开始 */
  onWfNodeStart?: (nodeId: string, nodeName: string, nodeType: string) => void;
  /** 工作流节点完成(status 为节点执行状态) */
  onWfNodeDone?: (nodeId: string, nodeName: string, nodeType: string, status: string) => void;
  /** 工作流节点失败 */
  onWfNodeError?: (nodeId: string, nodeName: string, nodeType: string, error: string) => void;
}

/** SSE 消费状态:游标与终态标志由分发函数更新,消费循环据此判定续传/退出 */
export interface SseState {
  runId: string;
  cursor: number;
  ok: boolean;
  failed: boolean;
}

/**
 * 单帧 SSE 事件分发(纯逻辑):解析 payload → 调用对应回调 → 更新 state。
 * 未知事件静默忽略(后端先行的扩展事件不影响旧前端)。
 */
export const dispatchStreamEvent = (
  state: SseState,
  eventName: string,
  payload: string,
  eventId: number,
  cb: StreamCallbacks
): void => {
  state.cursor = nextSseCursor(state.cursor, eventId);
  const ev = eventName || 'message';
  try {
    if (ev === 'run_id') {
      const d = JSON.parse(payload);
      if (d.runId) {
        state.runId = String(d.runId);
        cb.onRunId?.(state.runId);
      }
    } else if (ev === 'meta') {
      const meta = JSON.parse(payload);
      cb.onMeta?.(meta.sessionId);
    } else if (ev === 'delta') {
      const d = JSON.parse(payload);
      cb.onDelta?.(typeof d.text === 'string' ? d.text : typeof d.delta === 'string' ? d.delta : payload);
    } else if (ev === 'team_agent_start') {
      const d = JSON.parse(payload);
      cb.onTeamAgentStart?.(d.index, d.total, d.agentName, d.role);
    } else if (ev === 'team_delta') {
      const d = JSON.parse(payload);
      cb.onTeamAgentDelta?.(d.index, d.text);
    } else if (ev === 'team_agent_done') {
      const d = JSON.parse(payload);
      cb.onTeamAgentDone?.(d.index, d.content, d.totalTokens ?? null);
    } else if (ev === 'usage') {
      const d = JSON.parse(payload);
      cb.onUsage?.(d.sessionId, d.lastRoundTokens ?? 0);
    } else if (ev === 'wf_start') {
      // 体检 E1:工作流执行链事件(此前静默丢弃,聊天气泡无法看到节点进度)
      const d = JSON.parse(payload);
      cb.onWfStart?.(d.wfRunId, typeof d.mode === 'string' ? d.mode : '');
    } else if (ev === 'node_start') {
      const d = JSON.parse(payload);
      cb.onWfNodeStart?.(String(d.nodeId ?? ''), String(d.nodeName ?? ''), String(d.nodeType ?? ''));
    } else if (ev === 'node_done') {
      const d = JSON.parse(payload);
      cb.onWfNodeDone?.(String(d.nodeId ?? ''), String(d.nodeName ?? ''), String(d.nodeType ?? ''), String(d.status ?? ''));
    } else if (ev === 'node_error') {
      const d = JSON.parse(payload);
      cb.onWfNodeError?.(String(d.nodeId ?? ''), String(d.nodeName ?? ''), String(d.nodeType ?? ''), String(d.error ?? ''));
    } else if (ev === 'done') {
      state.ok = true;
      cb.onDone?.(JSON.parse(payload));
    } else if (ev === 'error') {
      state.failed = true;
      try {
        cb.onError?.(JSON.parse(payload).message || payload);
      } catch {
        cb.onError?.(payload);
      }
    }
  } catch {
    if (ev === 'error') {
      state.failed = true;
      cb.onError?.(payload);
    }
  }
};
