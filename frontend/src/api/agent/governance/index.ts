import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** ============ P1 / B1 版本快照 ============ */
export interface VersionItem {
  id: number;
  agentId: number;
  versionNo: number;
  configHash: string;
  remark: string;
  source: string;
  createTime: string;
}

export interface VersionDiff {
  from: number;
  to: number;
  changedFields: number;
  changes: Record<string, { from: string; to: string }>;
}

export const listVersions = (agentId: string | number): AxiosPromise<VersionItem[]> =>
  request({ url: '/agent/governance/versions', method: 'get', params: { agentId } });

export const getVersion = (agentId: string | number, versionNo: number): AxiosPromise<Record<string, any>> =>
  request({ url: '/agent/governance/version', method: 'get', params: { agentId, versionNo } });

export const diffVersions = (agentId: string | number, from: number, to: number): AxiosPromise<VersionDiff> =>
  request({ url: '/agent/governance/diff', method: 'get', params: { agentId, from, to } });

export const rollbackVersion = (agentId: string | number, versionNo: number) =>
  request({ url: '/agent/governance/rollback', method: 'post', data: { agentId, versionNo } });

/** ============ P1 / B3 发布门禁 ============ */
export interface GateResult {
  passed: boolean;
  waived: boolean;
  sampleAvailable: boolean;
  reason: string;
  checks: Record<string, any>;
}

export const previewGate = (agentId: string | number): AxiosPromise<GateResult> =>
  request({ url: '/agent/governance/gate/preview', method: 'get', params: { agentId } });

export const listGateExecutions = (agentId: string | number): AxiosPromise<any[]> =>
  request({ url: '/agent/governance/gate/executions', method: 'get', params: { agentId } });

export const listWaivers = (agentId: string | number): AxiosPromise<any[]> =>
  request({ url: '/agent/governance/gate/waivers', method: 'get', params: { agentId } });

export const requestWaiver = (agentId: string | number, reason: string): AxiosPromise<number> =>
  request({ url: '/agent/governance/gate/waiver', method: 'post', data: { agentId, reason } });

export const approveWaiver = (id: number, approve: boolean) =>
  request({ url: '/agent/governance/gate/waiver/approve', method: 'put', params: { id, approve } });

/** ============ P1 / B2 Prompt 模板中心 ============ */
export interface PromptTemplate {
  id: number;
  workspaceId: number;
  name: string;
  description?: string;
  category: string;
  stableVersionId?: number;
  status: string;
  createTime: string;
  content?: string;
  variableSchema?: string;
}

export interface PromptVersion {
  id: number;
  template_id: number;
  versionNo: number;
  contentHash: string;
  remark?: string;
  createTime: string;
  content?: string;
  variableSchema?: string;
}

export const listPromptTemplates = (workspaceId?: string | number): AxiosPromise<PromptTemplate[]> =>
  request({ url: '/agent/governance/prompts', method: 'get', params: { workspaceId } });

export const getPromptTemplate = (id: number): AxiosPromise<PromptTemplate> =>
  request({ url: '/agent/governance/prompt', method: 'get', params: { id } });

export const savePromptTemplate = (data: Record<string, any>): AxiosPromise<number> =>
  request({ url: '/agent/governance/prompt', method: 'post', data });

export const listPromptVersions = (templateId: number): AxiosPromise<PromptVersion[]> =>
  request({ url: '/agent/governance/prompt/versions', method: 'get', params: { templateId } });

export const getPromptVersion = (versionId: number): AxiosPromise<PromptVersion> =>
  request({ url: '/agent/governance/prompt/version', method: 'get', params: { versionId } });

export const publishPromptVersion = (data: Record<string, any>): AxiosPromise<number> =>
  request({ url: '/agent/governance/prompt/version', method: 'post', data });

export const setPromptStable = (templateId: number, versionId: number) =>
  request({ url: '/agent/governance/prompt/stable', method: 'post', data: { templateId, versionId } });

export const previewPrompt = (versionId: number, variables: string): AxiosPromise<Record<string, any>> =>
  request({ url: '/agent/governance/prompt/preview', method: 'post', data: { versionId, variables } });

/** C4:业务描述 → system prompt 草稿(不落库,人工确认后走模板发布) */
export interface PromptGenerateResult {
  ok: boolean;
  message?: string;
  content?: string;
  sections?: Record<string, any>;
  estimatedTokens?: number;
  modelName?: string;
}

export const generatePromptDraft = (data: Record<string, any>): AxiosPromise<PromptGenerateResult> =>
  request({ url: '/agent/governance/prompt/generate', method: 'post', data });

/** C4:草稿内容直接渲染预览(不落版本链) */
export const previewPromptDraft = (data: Record<string, any>): AxiosPromise<Record<string, any>> =>
  request({ url: '/agent/governance/prompt/preview-draft', method: 'post', data });

/** ============ P1 / B3 效果评测 ============ */
export interface EvalDataset {
  id: number;
  name: string;
  description?: string;
  workspaceId: number;
  caseCount: number;
  createTime: string;
}

export interface EvalCase {
  id: number;
  question: string;
  expectedKeywords?: string;
  reference_answer?: string;
}

export interface EvalRun {
  id: number;
  status: string;
  avgScore?: number;
  totalCases: number;
  finishedCases: number;
  p95LatencyMs?: number;
  refusalRate?: number;
  toolSuccessRate?: number;
  judgeEnabled: string;
  createTime: string;
}

export const listEvalDatasets = (workspaceId?: string | number): AxiosPromise<EvalDataset[]> =>
  request({ url: '/agent/governance/eval/datasets', method: 'get', params: { workspaceId } });

export const createEvalDataset = (data: Record<string, any>): AxiosPromise<number> =>
  request({ url: '/agent/governance/eval/dataset', method: 'post', data });

export const listEvalCases = (datasetId: number): AxiosPromise<EvalCase[]> =>
  request({ url: '/agent/governance/eval/cases', method: 'get', params: { datasetId } });

export const addEvalCases = (datasetId: number, cases: Record<string, any>[]): AxiosPromise<number> =>
  request({ url: '/agent/governance/eval/cases', method: 'post', data: { datasetId, cases } });

export const runEval = (datasetId: number, agentId: string | number, judgeEnabled: boolean) =>
  request({ url: '/agent/governance/eval/run', method: 'post', data: { datasetId, agentId, judgeEnabled }, timeout: 600000 });

export const getEvalRun = (runId: number): AxiosPromise<{ run: EvalRun; results: any[] }> =>
  request({ url: '/agent/governance/eval/run', method: 'get', params: { runId } });

export const listEvalRuns = (agentId: string | number): AxiosPromise<EvalRun[]> =>
  request({ url: '/agent/governance/eval/runs', method: 'get', params: { agentId } });

export const createBaseline = (runId: number): AxiosPromise<number> =>
  request({ url: '/agent/governance/eval/baseline', method: 'post', params: { runId } });
