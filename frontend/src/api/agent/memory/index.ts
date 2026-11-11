import type { MemoryForm, MemoryQuery, MemoryVO, MemorySearchHit, MemoryGraphNode, MemoryGraphEdge } from '@/api/agent/memory/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询 Agent 长期记忆 */
export const listMemory = (query: MemoryQuery): AxiosPromise<PageResult<MemoryVO>> => {
  return request({
    url: '/agent/memory/list',
    method: 'get',
    params: query
  });
};

/** 手动新增记忆 */
export const addMemory = (data: MemoryForm) => {
  return request({
    url: '/agent/memory',
    method: 'post',
    data: data
  });
};

/** 删除记忆 */
export const delMemory = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/memory/' + ids,
    method: 'delete'
  });
};

/** 清空 Agent 全部记忆 */
export const clearMemory = (agentId: string | number) => {
  return request({
    url: `/agent/memory/agent/${agentId}`,
    method: 'delete'
  });
};

/** 语义检索(P3/M2'):向量可用走语义召回,不可用回退评分排序 */
export const searchMemory = (agentId: number | string, query: string, userId?: number | string, limit = 10): AxiosPromise<MemorySearchHit[]> => {
  return request({
    url: '/agent/memory/search',
    method: 'get',
    params: { agentId, query, userId, limit }
  });
};

/** 注入预览:返回与对话真实注入一致的记忆块(含图谱关联背景) */
export const previewMemoryBlock = (agentId: number | string, query: string, userId?: number | string): AxiosPromise<string> => {
  return request({
    url: '/agent/memory/preview',
    method: 'get',
    params: { agentId, query, userId }
  });
};

/** 存量记忆补建图谱(异步,会消耗模型调用) */
export const rebuildMemoryGraph = (agentId: number | string) => {
  return request({
    url: '/agent/memory/graph/rebuild',
    method: 'post',
    params: { agentId }
  });
};

/** 图谱-实体 TOP(userId 为空只看平台级) */
export const listMemoryGraphNodes = (agentId: number | string, userId?: number | string, limit = 100): AxiosPromise<MemoryGraphNode[]> => {
  return request({
    url: '/agent/memory/graph/nodes',
    method: 'get',
    params: { agentId, userId, limit }
  });
};

/** 图谱-关系边 */
export const listMemoryGraphEdges = (agentId: number | string, userId?: number | string, limit = 200): AxiosPromise<MemoryGraphEdge[]> => {
  return request({
    url: '/agent/memory/graph/edges',
    method: 'get',
    params: { agentId, userId, limit }
  });
};

/**
 * 用户级遗忘(L5):级联删除 记忆(含向量)→图谱→归档表。
 * confirm 必须为 FORGET;平台级记忆不在遗忘范围。
 */
export const forgetMemoryUser = (agentId: number | string, userId: number | string, confirm: string): AxiosPromise<number> => {
  return request({
    url: `/agent/memory/user/${userId}/forget`,
    method: 'delete',
    params: { agentId, confirm }
  });
};

/** 手动触发一次记忆治理(消息归档+TTL+配额);治理未启用时报错 */
export const runMemoryGovernance = (): AxiosPromise<Record<string, number>> => {
  return request({
    url: '/agent/memory/governance/run',
    method: 'post'
  });
};
