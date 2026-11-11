import request from '@/utils/request';
import type { AxiosPromise } from '@/utils/api-types';

/** 工作流 API 端点视图对象 */
export interface WorkflowEndpointVO {
  id: number | string;
  agentId: number | string;
  agentName?: string;
  workspaceId?: number | string;
  endpointCode: string;
  /** 调用地址(相对路径) */
  invokePath?: string;
  name?: string;
  apiKeyId?: number | string | null;
  /** 绑定密钥脱敏展示 */
  maskedKey?: string;
  rateLimit?: number;
  timeoutSeconds?: number;
  status: string;
  createTime?: string;
}

/** 工作流 API 端点表单 */
export interface WorkflowEndpointForm {
  id?: number | string;
  agentId: number | string;
  endpointCode: string;
  name?: string;
  apiKeyId?: number | string | null;
  rateLimit?: number;
  timeoutSeconds?: number;
  status?: string;
}

/** 分页查询端点 */
export const listWorkflowEndpoint = (agentId: number | string, pageNum: number, pageSize: number): AxiosPromise<{ rows: WorkflowEndpointVO[]; total: number }> => {
  return request({
    url: '/agent/workflow-endpoint/list',
    method: 'get',
    params: { agentId, pageNum, pageSize }
  });
};

/** 创建端点 */
export const addWorkflowEndpoint = (data: WorkflowEndpointForm): AxiosPromise<number> => {
  return request({
    url: '/agent/workflow-endpoint',
    method: 'post',
    data
  });
};

/** 更新端点(编码与归属不可改) */
export const updateWorkflowEndpoint = (data: WorkflowEndpointForm): AxiosPromise<boolean> => {
  return request({
    url: '/agent/workflow-endpoint',
    method: 'put',
    data
  });
};

/** 启用/停用 */
export const changeWorkflowEndpointStatus = (id: number | string, status: string): AxiosPromise<boolean> => {
  return request({
    url: `/agent/workflow-endpoint/${id}/status/${status}`,
    method: 'put'
  });
};

/** 删除端点 */
export const delWorkflowEndpoint = (ids: Array<number | string>): AxiosPromise<boolean> => {
  return request({
    url: `/agent/workflow-endpoint/${ids.join(',')}`,
    method: 'delete'
  });
};
