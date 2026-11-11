import request from '@/utils/request';
import type { AxiosPromise } from '@/utils/api-types';
import { getToken } from '@/utils/auth';
import { nextSseCursor } from '@/utils/governance';

/** 工作流图保存参数 */
export interface WorkflowSaveParams {
  agentId: number | string;
  workflowJson: string;
}

/** 工作流试运行参数 */
export interface WorkflowTrialRunParams extends WorkflowSaveParams {
  inputs?: string;
  /** W8:空跑模式(外部调用节点返回桩输出) */
  dryRun?: boolean;
  /** W8:从指定节点起跑 */
  startNodeKey?: string;
  /** W8:图级步数上限(1-500) */
  maxSteps?: number;
}

/** 工作流校验问题项 */
export interface WorkflowIssue {
  nodeId?: string;
  edgeId?: string;
  message: string;
}

/** 工作流校验结果 */
export interface WorkflowValidateResult {
  valid: boolean;
  blockers: WorkflowIssue[];
  warnings: WorkflowIssue[];
}

/** 试运行节点轨迹 */
export interface WorkflowNodeTrace {
  nodeId: string;
  nodeName: string;
  nodeType: string;
  status: string;
  output?: string;
}

/** W9:工作流执行事件(逐节点推送) */
export interface WorkflowRunEvent {
  sequence: number;
  name: string;
  data: string;
}

/** 试运行结果 */
export interface WorkflowTrialRunResult {
  status: string;
  message: string;
  inputs: string;
  trace: WorkflowNodeTrace[];
  /** 暂停时返回的运行记录ID */
  runId?: number | string;
  /** 暂停时生成的审批单ID */
  approvalId?: number | string;
}

/** 保存工作流图(返回新版本号) */
export const saveWorkflow = (data: WorkflowSaveParams): AxiosPromise<number> => {
  return request({
    url: '/agent/workflow/save',
    method: 'post',
    data
  });
};

/** 校验工作流图 */
export const validateWorkflow = (data: WorkflowSaveParams): AxiosPromise<WorkflowValidateResult> => {
  return request({
    url: '/agent/workflow/validate',
    method: 'post',
    data
  });
};

/** 试运行工作流(执行含 LLM 节点,耗时可能远超全局默认 15s) */
export const trialRunWorkflow = (data: WorkflowTrialRunParams): AxiosPromise<WorkflowTrialRunResult> => {
  return request({
    url: '/agent/workflow/trial-run',
    method: 'post',
    data,
    timeout: 600000
  });
};

/** W9:异步试运行(立即返回运行实例 ID,进度走 runEvents 订阅) */
export const trialRunWorkflowAsync = (data: WorkflowTrialRunParams): AxiosPromise<number> => {
  return request({
    url: '/agent/workflow/trial-run/async',
    method: 'post',
    data
  });
};

/** W9:执行事件回调 */
export interface WorkflowRunEventCallbacks {
  onNodeStart?: (nodeId: string, nodeName: string, nodeType: string) => void;
  onNodeDone?: (nodeId: string, nodeName: string, nodeType: string, status: string, output: string) => void;
  onNodeError?: (nodeId: string, nodeName: string, nodeType: string, error: string) => void;
  onApprovalPending?: (nodeId: string, approvalId: number | string) => void;
  onDone?: (status: string, message: string) => void;
}

/**
 * W9:订阅工作流执行事件(fetch + ReadableStream),先重放 after 之后的事件再跟随至终态;
 * 断线自动带 after 游标重连,最多 3 次。返回 Promise<boolean>:true 表示收到终态。
 */
export const consumeWorkflowRunEvents = async (
  runId: number | string,
  after: number,
  cb: WorkflowRunEventCallbacks,
  signal?: AbortSignal
): Promise<boolean> => {
  const base = import.meta.env.VITE_APP_BASE_API;
  const headers = {
    'Authorization': 'Bearer ' + getToken(),
    'clientid': import.meta.env.VITE_APP_CLIENT_ID
  };
  let cursor = after;
  let terminated = false;
  const dispatch = (eventName: string, payload: string, eventId: number) => {
    cursor = nextSseCursor(cursor, eventId);
    try {
      const d = payload ? JSON.parse(payload) : {};
      if (eventName === 'node_start') {
        cb.onNodeStart?.(d.nodeId, d.nodeName, d.nodeType);
      } else if (eventName === 'node_done') {
        cb.onNodeDone?.(d.nodeId, d.nodeName, d.nodeType, d.status ?? 'success', d.output ?? '');
      } else if (eventName === 'node_error') {
        cb.onNodeError?.(d.nodeId, d.nodeName, d.nodeType, d.error ?? '');
      } else if (eventName === 'approval_pending') {
        cb.onApprovalPending?.(d.nodeId, d.approvalId);
      } else if (eventName === 'done') {
        cb.onDone?.(d.status ?? 'fail', d.message ?? '');
        terminated = true;
      } else if (eventName === 'terminal') {
        terminated = true;
      }
    } catch {
      // 非法载荷跳过
    }
  };

  const initialCursor = after;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const resp = await fetch(`${base}/agent/workflow/run/${runId}/events?after=${cursor}`, {
        method: 'GET',
        headers,
        signal
      });
      if (!resp.ok || !resp.body) {
        throw new Error(`HTTP ${resp.status}`);
      }
      const reader = resp.body.getReader();
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
      // 收到终态即结束;连接断开但未到终态时续连接传
      if (terminated) break;
      if (cursor === initialCursor) {
        // 没有任何新事件且连接已断开,视为执行已结束(终态标记可能尚未落 Redis)
        break;
      }
    } catch (e) {
      if (signal?.aborted) return false;
      if (attempt === 2) throw e;
      await new Promise((r) => setTimeout(r, 800 * (attempt + 1)));
    }
  }
  return terminated;
};

/** 工作流版本项 */
export interface WorkflowVersionVO {
  id: number | string;
  agentId: number | string;
  version: number;
  remark?: string;
  workflowJson?: string;
  createBy?: number | string;
  createTime?: string;
}

/** 运行历史项 */
export interface WorkflowRunVO {
  id: number | string;
  agentId: number | string;
  status: string;
  inputs?: string;
  pendingNode?: string;
  approvalId?: number | string;
  userId?: number | string;
  createTime?: string;
}

/** 运行回放详情 */
export interface WorkflowRunDetailVO extends WorkflowRunVO {
  workflowJson: string;
  varsJson?: string;
  trace: WorkflowNodeTrace[];
}

/** 版本历史列表 */
export const listWorkflowVersion = (agentId: number | string): AxiosPromise<WorkflowVersionVO[]> => {
  return request({
    url: '/agent/workflow/version/list',
    method: 'get',
    params: { agentId }
  });
};

/** 版本详情(含图快照) */
export const getWorkflowVersion = (versionId: number | string): AxiosPromise<WorkflowVersionVO> => {
  return request({
    url: `/agent/workflow/version/${versionId}`,
    method: 'get'
  });
};

/** 回滚到指定版本(返回新版本号) */
export const rollbackWorkflowVersion = (versionId: number | string): AxiosPromise<number> => {
  return request({
    url: `/agent/workflow/version/${versionId}/rollback`,
    method: 'post'
  });
};

/** 运行历史分页列表 */
export const listWorkflowRun = (agentId: number | string, pageNum: number, pageSize: number): AxiosPromise<{ rows: WorkflowRunVO[]; total: number }> => {
  return request({
    url: '/agent/workflow/run/list',
    method: 'get',
    params: { agentId, pageNum, pageSize }
  });
};

/** 运行回放详情 */
export const getWorkflowRun = (runId: number | string): AxiosPromise<WorkflowRunDetailVO> => {
  return request({
    url: `/agent/workflow/run/${runId}`,
    method: 'get'
  });
};
