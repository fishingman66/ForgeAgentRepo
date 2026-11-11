import type { ChatAttachment, ChatSendForm, ChatSendResult, MessageVO, SessionQuery, SessionUsageBar, SessionVO } from '@/api/agent/session/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import { getToken } from '@/utils/auth';
import request from '@/utils/request';
import { dispatchStreamEvent, type SseState, type StreamCallbacks } from './sse-events';

/** 体检 E3:回调接口与事件分发提取到 sse-events.ts(可单测),此处 re-export 保持对外签名不变 */
export type { StreamCallbacks } from './sse-events';

/** 我的会话分页列表 */
export const listSession = (query: SessionQuery): AxiosPromise<PageResult<SessionVO>> => {
  return request({
    url: '/agent/session/list',
    method: 'get',
    params: query
  });
};

/** 新建会话 */
export const addSession = (agentId: string | number, title?: string): AxiosPromise<SessionVO> => {
  return request({
    url: '/agent/session',
    method: 'post',
    data: { agentId, title }
  });
};

/** 重命名会话 */
export const renameSession = (id: string | number, title: string) => {
  return request({
    url: '/agent/session',
    method: 'put',
    data: { id, title }
  });
};

/** 置顶/取消置顶会话 */
export const pinSession = (id: string | number, pinned: '0' | '1') => {
  return request({
    url: `/agent/session/${id}/pinned`,
    method: 'put',
    params: { pinned }
  });
};

/** 删除会话 */
export const delSession = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/session/' + ids,
    method: 'delete'
  });
};

/** 会话消息列表(游标分页:beforeId 取更早一页,返回值按时间正序) */
export const listMessages = (
  id: string | number,
  params?: { beforeId?: string | number; limit?: number }
): AxiosPromise<MessageVO[]> => {
  return request({
    url: `/agent/session/${id}/messages`,
    method: 'get',
    params
  });
};

/** 会话 Token 用量实时反馈(UX-03) */
export const getSessionUsage = (id: string | number): AxiosPromise<SessionUsageBar> => {
  return request({
    url: `/agent/session/${id}/usage`,
    method: 'get'
  });
};

/** 发送消息(超时放宽到 10 分钟,审批等待/长轮次可能较久) */
export const sendChat = (data: ChatSendForm): AxiosPromise<ChatSendResult> => {
  return request({
    url: '/agent/chat/send',
    method: 'post',
    data: data,
    timeout: 600000
  });
};

/** 上传会话附件(图片或文档,选中后立即上传,返回附件摘要) */
export const uploadChatAttachment = (file: File, params?: { agentId?: string | number; sessionId?: string | number }): AxiosPromise<ChatAttachment> => {
  const formData = new FormData();
  formData.append('file', file);
  return request({
    url: '/agent/chat/attachment/upload',
    method: 'post',
    data: formData,
    params,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  });
};

/** 查询附件解析状态(文档上传后轮询) */
export const getChatAttachmentMeta = (id: string | number): AxiosPromise<ChatAttachment> => {
  return request({
    url: `/agent/chat/attachment/${id}/meta`,
    method: 'get'
  });
};

/** 以登录态拉取原图并转为本地 objectURL(调用方负责 revokeObjectURL) */
export const fetchChatAttachmentObjectUrl = async (id: string | number): Promise<string> => {
  const resp = await fetch(import.meta.env.VITE_APP_BASE_API + `/agent/chat/attachment/${id}`, {
    method: 'GET',
    headers: {
      'Authorization': 'Bearer ' + getToken(),
      'clientid': import.meta.env.VITE_APP_CLIENT_ID
    }
  });
  if (!resp.ok) {
    throw new Error('原图加载失败');
  }
  const blob = await resp.blob();
  return URL.createObjectURL(blob);
};

/** 中断当前会话进行中的对话轮次 */
export const interruptChat = (sessionId: string | number) => {
  return request({
    url: `/agent/chat/${sessionId}/interrupt`,
    method: 'post'
  });
};

/** 重新生成最后一条助手回复(阻塞式) */
export const regenerateChat = (sessionId: string | number): AxiosPromise<ChatSendResult> => {
  return request({
    url: `/agent/chat/${sessionId}/regenerate`,
    method: 'post',
    timeout: 600000
  });
};

/** 消息反馈:like 点赞 / dislike 点踩 / 空(取消);点踩可携标签与备注 */
export const feedbackMessage = (messageId: string | number, value?: 'like' | 'dislike' | '', comment?: string, tags?: string[]) => {
  return request({
    url: `/agent/chat/message/${messageId}/feedback`,
    method: 'post',
    params: { value: value || '', comment: comment || undefined, tags: tags?.length ? tags.join(',') : undefined }
  });
};

/** 撤回末轮用户消息(删除该消息及其后的助手回复) */
export const recallMessage = (messageId: string | number) => {
  return request({
    url: `/agent/chat/message/${messageId}`,
    method: 'delete'
  });
};

/** 运行链路步骤(P0 / A2) */
export interface RunStepVO {
  step_no: number;
  step_type: string;
  step_name: string;
  status: string;
  latency_ms: number | null;
  token_usage: number | null;
  payload: string | null;
  create_time: string;
}

/** 运行链路详情 */
export interface RunDetailVO {
  run: Record<string, any>;
  steps: RunStepVO[];
}

/** 查询某次运行的链路详情(主记录 + 按序步骤) */
export const getRunDetail = (runId: string): AxiosPromise<RunDetailVO> => {
  return request({
    url: `/agent/chat/run/${encodeURIComponent(runId)}`,
    method: 'get'
  });
};

/** 流式发送回调集合已移至 sse-events.ts(见顶部 re-export) */

/** 断线续传最大尝试次数 */
const MAX_RECONNECT = 3;

/**
 * 流式发送消息(SSE):以 fetch + ReadableStream 消费后端事件流。
 * 事件序列 run_id → meta → delta* → usage → done,error 事件表示执行失败。
 *
 * P0/A1:记录每帧 id 作为断点游标;连接异常中断且已拿到 runId 时,
 * 携带 after=cursor 请求 /agent/chat/run/{runId}/events 重放剩余事件,最多 3 次。
 * 返回 Promise<boolean>:true 表示正常收到 done。
 */
export const streamChat = async (data: ChatSendForm, cb: StreamCallbacks, signal?: AbortSignal): Promise<boolean> => {
  return consumeSse('/agent/chat/stream', {
    method: 'POST',
    body: JSON.stringify(data),
    signal
  }, cb);
};

/**
 * UX-01 流式调试对话(SSE):复用临时会话,按 draft 覆盖 Agent 配置执行;
 * 事件序列与 streamChat 一致。
 */
export const streamDebugChat = async (data: ChatSendForm, cb: StreamCallbacks, signal?: AbortSignal): Promise<boolean> => {
  return consumeSse('/agent/chat/debug/stream', {
    method: 'POST',
    body: JSON.stringify(data),
    signal
  }, cb);
};

/**
 * 流式重新生成最后一条助手回复(SSE):事件序列 run_id → delta* → done。
 */
export const streamRegenerate = async (sessionId: string | number, cb: StreamCallbacks, signal?: AbortSignal): Promise<boolean> => {
  return consumeSse(`/agent/chat/${sessionId}/regenerate/stream`, { method: 'POST', signal }, cb);
};

/** 通用 SSE 消费:事件帧解析、断点游标与断线续传 */
const consumeSse = async (url: string, init: RequestInit, cb: StreamCallbacks): Promise<boolean> => {
  const base = import.meta.env.VITE_APP_BASE_API;
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + getToken(),
    'clientid': import.meta.env.VITE_APP_CLIENT_ID
  };
  const signal = init.signal ?? undefined;
  // 体检 E3:状态收拢进 SseState,事件分发委托给可单测的 dispatchStreamEvent
  const state: SseState = { runId: '', cursor: 0, ok: false, failed: false };

  const dispatch = (eventName: string, payload: string, eventId: number) => {
    dispatchStreamEvent(state, eventName, payload, eventId, cb);
  };

  /** 解析一条 SSE 连接,按帧回填游标并派发事件 */
  const readStream = async (resp: Response) => {
    const reader = resp.body!.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let eventName = '';
    let dataLines: string[] = [];
    let eventId = 0;
    const flush = () => {
      if (!eventName && dataLines.length === 0 && eventId === 0) return;
      dispatch(eventName, dataLines.join('\n'), eventId);
      eventName = '';
      dataLines = [];
      eventId = 0;
    };
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buffer.indexOf('\n')) > -1) {
        let line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (line === '') {
          flush();
        } else if (line.startsWith('event:')) {
          eventName = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).startsWith(' ') ? line.slice(6) : line.slice(5));
        } else if (line.startsWith('id:')) {
          eventId = Number(line.slice(3).trim()) || 0;
        }
      }
    }
    flush();
  };

  const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

  try {
    const first = await fetch(base + url, { method: 'POST', headers, ...init });
    if (!first.ok || !first.body) {
      cb.onError?.(`流式连接失败(HTTP ${first.status})`);
      cb.onClose?.();
      return false;
    }
    try {
      await readStream(first);
    } catch (e: any) {
      // 组件卸载主动中止:直接结束,不续传
      if (e?.name === 'AbortError') throw e;
    }
    for (let attempt = 1; attempt <= MAX_RECONNECT; attempt++) {
      // ok / failed / runId 由 readStream 内部的事件派发更新,在此显式判定后退出
      if (state.ok || state.failed || !state.runId || signal?.aborted) break;
      cb.onReconnect?.(attempt);
      await wait(300 * attempt);
      try {
        const replay = await fetch(base + `/agent/chat/run/${encodeURIComponent(state.runId)}/events?after=${state.cursor}`, {
          method: 'GET',
          headers,
          signal
        });
        if (!replay.ok || !replay.body) break;
        await readStream(replay);
      } catch (e: any) {
        if (e?.name === 'AbortError') throw e;
      }
    }
    if (!state.ok && !state.failed) {
      cb.onError?.('连接中断，未能恢复本次生成');
    }
    cb.onClose?.();
    return state.ok;
  } catch (e: any) {
    if (e?.name === 'AbortError') {
      cb.onClose?.();
      return false;
    }
    cb.onError?.(e?.message || '流式连接异常');
    cb.onClose?.();
    return false;
  }
};
