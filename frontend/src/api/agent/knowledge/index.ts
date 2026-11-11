import type { KnowledgeForm, KnowledgeQuery, KnowledgeVO, KnowledgeDocumentQuery, KnowledgeDocumentVO, KnowledgeChunkVO, KnowledgeSearchForm, KnowledgeSearchHitVO, KnowledgeZeroHitQuery, KnowledgeZeroHitVO, KbEvalCaseVO, KbEvalRunVO, KbRebuildProgressVO } from '@/api/agent/knowledge/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询知识库 */
export const listKnowledge = (query: KnowledgeQuery): AxiosPromise<PageResult<KnowledgeVO>> => {
  return request({
    url: '/agent/knowledge/list',
    method: 'get',
    params: query
  });
};

/** 知识库详情 */
export const getKnowledge = (id: string | number): AxiosPromise<KnowledgeVO> => {
  return request({
    url: '/agent/knowledge/' + id,
    method: 'get'
  });
};

/** 新增知识库 */
export const addKnowledge = (data: KnowledgeForm) => {
  return request({
    url: '/agent/knowledge',
    method: 'post',
    data: data
  });
};

/** 修改知识库 */
export const updateKnowledge = (data: KnowledgeForm) => {
  return request({
    url: '/agent/knowledge',
    method: 'put',
    data: data
  });
};

/** 启用/停用知识库 */
export const changeKnowledgeStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/knowledge/status',
    method: 'put',
    params: { id, status }
  });
};

/** 重建知识库索引(全量:所有文档重做) */
export const rebuildKnowledgeIndex = (id: string | number) => {
  return request({
    url: '/agent/knowledge/' + id + '/reindex',
    method: 'put'
  });
};

/** 差异重建:只重建配置过期的文档,返回触发的文档数(0=无需重建) */
export const rebuildStaleKnowledge = (id: string | number): AxiosPromise<number> => {
  return request({
    url: '/agent/knowledge/' + id + '/reindex-stale',
    method: 'put'
  });
};

/** 查询索引重建进度 */
export const getRebuildProgress = (id: string | number): AxiosPromise<KbRebuildProgressVO> => {
  return request({
    url: '/agent/knowledge/' + id + '/reindex/progress',
    method: 'get'
  });
};

/** 删除知识库 */
export const delKnowledge = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/knowledge/' + ids,
    method: 'delete'
  });
};

/** 向量检索 */
export const searchKnowledge = (data: KnowledgeSearchForm): AxiosPromise<KnowledgeSearchHitVO[]> => {
  return request({
    url: '/agent/knowledge/search',
    method: 'post',
    data: data
  });
};

/** 分页查询知识库文档 */
export const listKnowledgeDocument = (query: KnowledgeDocumentQuery): AxiosPromise<PageResult<KnowledgeDocumentVO>> => {
  return request({
    url: '/agent/knowledge/doc/list',
    method: 'get',
    params: query
  });
};

/** 上传知识库文档 */
export const uploadKnowledgeDocument = (knowledgeId: string | number, file: File) => {
  const formData = new FormData();
  formData.append('knowledgeId', String(knowledgeId));
  formData.append('file', file);
  return request({
    url: '/agent/knowledge/doc/upload',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  });
};

/** 重试失败文档 */
export const retryKnowledgeDocument = (id: string | number) => {
  return request({
    url: `/agent/knowledge/doc/retry/${id}`,
    method: 'post'
  });
};

/** 删除知识库文档 */
export const delKnowledgeDocument = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/knowledge/doc/' + ids,
    method: 'delete'
  });
};

/** 查询文档分块 */
export const listKnowledgeChunks = (documentId: string | number): AxiosPromise<KnowledgeChunkVO[]> => {
  return request({
    url: `/agent/knowledge/doc/chunks/${documentId}`,
    method: 'get'
  });
};

/** 编辑分块内容(自动重新向量化) */
export const updateKnowledgeChunk = (data: { id: string | number; content: string }) => {
  return request({
    url: '/agent/knowledge/doc/chunk',
    method: 'put',
    data: data
  });
};

/** 启用/禁用分块 */
export const changeKnowledgeChunkEnabled = (id: string | number, enabled: boolean) => {
  return request({
    url: '/agent/knowledge/doc/chunk/enabled',
    method: 'put',
    params: { id, enabled }
  });
};

/** 下载文档 */
export const downloadKnowledgeDocument = (id: string | number) => {
  return request({
    url: `/agent/knowledge/doc/download/${id}`,
    method: 'get',
    responseType: 'blob'
  });
};

/** 分页查询零命中查询日志 */
export const listKnowledgeZeroHit = (query: KnowledgeZeroHitQuery): AxiosPromise<PageResult<KnowledgeZeroHitVO>> => {
  return request({
    url: '/agent/knowledge/zerohit/list',
    method: 'get',
    params: query
  });
};

/** 评测用例列表 */
export const listKbEvalCases = (knowledgeId: string | number): AxiosPromise<KbEvalCaseVO[]> => {
  return request({
    url: '/agent/kb/eval/cases',
    method: 'get',
    params: { knowledgeId }
  });
};

/** 新增评测用例 */
export const addKbEvalCase = (data: KbEvalCaseVO) => {
  return request({
    url: '/agent/kb/eval/case',
    method: 'post',
    data: data
  });
};

/** 删除评测用例 */
export const delKbEvalCase = (id: string | number) => {
  return request({
    url: '/agent/kb/eval/case/' + id,
    method: 'delete'
  });
};

/** 运行检索评测(返回 run 汇总 + hitRate + caseResults 明细) */
export const runKbEval = (knowledgeId: string | number, topK?: number): AxiosPromise<Record<string, any>> => {
  return request({
    url: '/agent/kb/eval/run',
    method: 'post',
    params: { knowledgeId, topK },
    timeout: 300000
  });
};

/** 评测运行历史 */
export const listKbEvalRuns = (knowledgeId: string | number, limit?: number): AxiosPromise<KbEvalRunVO[]> => {
  return request({
    url: '/agent/kb/eval/runs',
    method: 'get',
    params: { knowledgeId, limit }
  });
};
