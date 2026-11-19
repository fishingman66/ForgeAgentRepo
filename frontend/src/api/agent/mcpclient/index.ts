import type { McpClientForm, McpClientQuery, McpClientTokenVO, McpClientVO } from '@/api/agent/mcpclient/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询客户端 */
export const listMcpClient = (query: McpClientQuery): AxiosPromise<PageResult<McpClientVO>> => {
  return request({
    url: '/agent/mcpclient/list',
    method: 'get',
    params: query
  });
};

/** 客户端详情 */
export const getMcpClient = (id: string | number): AxiosPromise<McpClientVO> => {
  return request({
    url: '/agent/mcpclient/' + id,
    method: 'get'
  });
};

/** 新建客户端:响应里的 token 只出现这一次 */
export const addMcpClient = (data: McpClientForm): AxiosPromise<McpClientTokenVO> => {
  return request({
    url: '/agent/mcpclient',
    method: 'post',
    data: data
  });
};

/** 修改客户端(名称/空间/授权域/过期时间/备注) */
export const updateMcpClient = (data: McpClientForm) => {
  return request({
    url: '/agent/mcpclient',
    method: 'put',
    data: data
  });
};

/** 启用/停用 */
export const changeMcpClientStatus = (id: string | number, enabled: string) => {
  return request({
    url: '/agent/mcpclient/status',
    method: 'put',
    params: { id, enabled }
  });
};

/** 轮转令牌:旧令牌立即失效 */
export const rotateMcpClientToken = (id: string | number): AxiosPromise<McpClientTokenVO> => {
  return request({
    url: `/agent/mcpclient/${id}/rotate`,
    method: 'post'
  });
};

/** 删除客户端 */
export const delMcpClient = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/mcpclient/' + ids,
    method: 'delete'
  });
};
