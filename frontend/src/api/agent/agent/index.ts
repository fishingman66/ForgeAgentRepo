import type { AgentForm, AgentQuery, AgentVO } from '@/api/agent/agent/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询 Agent */
export const listAgent = (query: AgentQuery): AxiosPromise<PageResult<AgentVO>> => {
  return request({
    url: '/agent/agent/list',
    method: 'get',
    params: query
  });
};

/** 已发布 Agent 列表 */
export const listPublishedAgent = (): AxiosPromise<AgentVO[]> => {
  return request({
    url: '/agent/agent/published',
    method: 'get'
  });
};

/** Agent 详情 */
export const getAgent = (id: string | number): AxiosPromise<AgentVO> => {
  return request({
    url: '/agent/agent/' + id,
    method: 'get'
  });
};

/** 新增 Agent(返回新ID) */
export const addAgent = (data: AgentForm): AxiosPromise<string | number> => {
  return request({
    url: '/agent/agent',
    method: 'post',
    data: data
  });
};

/** 修改 Agent */
export const updateAgent = (data: AgentForm) => {
  return request({
    url: '/agent/agent',
    method: 'put',
    data: data
  });
};

/** 发布/停用 Agent(status: 1发布 2停用) */
export const changeAgentStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/agent/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除 Agent */
export const delAgent = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/agent/' + ids,
    method: 'delete'
  });
};

/** 克隆 Agent(返回新Agent ID) */
export const cloneAgent = (id: string | number): AxiosPromise<string | number> => {
  return request({
    url: '/agent/agent/' + id + '/clone',
    method: 'post'
  });
};
