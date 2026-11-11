import type {
  SemanticDimensionForm,
  SemanticDimensionVO,
  SemanticMetricForm,
  SemanticMetricVO,
  SemanticValueForm,
  SemanticValueVO
} from '@/api/agent/semantic/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

const BASE = '/agent/semantic';

// ---------- 指标 ----------
export const listSemanticMetrics = (datasourceId: string | number): AxiosPromise<SemanticMetricVO[]> =>
  request({ url: `${BASE}/${datasourceId}/metrics`, method: 'get' });

export const addSemanticMetric = (data: SemanticMetricForm) =>
  request({ url: `${BASE}/metric`, method: 'post', data });

export const updateSemanticMetric = (data: SemanticMetricForm) =>
  request({ url: `${BASE}/metric`, method: 'put', data });

export const delSemanticMetric = (id: string | number) =>
  request({ url: `${BASE}/metric/${id}`, method: 'delete' });

// ---------- 维度 ----------
export const listSemanticDimensions = (datasourceId: string | number): AxiosPromise<SemanticDimensionVO[]> =>
  request({ url: `${BASE}/${datasourceId}/dimensions`, method: 'get' });

export const addSemanticDimension = (data: SemanticDimensionForm) =>
  request({ url: `${BASE}/dimension`, method: 'post', data });

export const updateSemanticDimension = (data: SemanticDimensionForm) =>
  request({ url: `${BASE}/dimension`, method: 'put', data });

export const delSemanticDimension = (id: string | number) =>
  request({ url: `${BASE}/dimension/${id}`, method: 'delete' });

export const importSemanticDimensions = (datasourceId: string | number): AxiosPromise<number> =>
  request({ url: `${BASE}/${datasourceId}/dimensions/import`, method: 'post', data: {} });

// ---------- 枚举值字典 ----------
export const listSemanticValues = (
  datasourceId: string | number,
  tableName?: string,
  columnName?: string
): AxiosPromise<SemanticValueVO[]> =>
  request({
    url: `${BASE}/${datasourceId}/values`,
    method: 'get',
    params: { tableName, columnName }
  });

export const addSemanticValue = (data: SemanticValueForm) =>
  request({ url: `${BASE}/value`, method: 'post', data });

export const updateSemanticValue = (data: SemanticValueForm) =>
  request({ url: `${BASE}/value`, method: 'put', data });

export const batchAddSemanticValues = (data: SemanticValueForm[]) =>
  request({ url: `${BASE}/values/batch`, method: 'post', data });

export const delSemanticValue = (id: string | number) =>
  request({ url: `${BASE}/value/${id}`, method: 'delete' });

/** 采样指定列的 distinct 值 */
export const sampleColumnValues = (
  datasourceId: string | number,
  tableName: string,
  columnName: string,
  limit = 100
): AxiosPromise<string[]> =>
  request({
    url: `${BASE}/${datasourceId}/columns/${tableName}/${columnName}/sample`,
    method: 'get',
    params: { limit }
  });
