import type { McpServerForm, McpServerQuery, McpServerVO } from '@/api/agent/mcp/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询 MCP 服务器 */
export const listMcpServer = (query: McpServerQuery): AxiosPromise<PageResult<McpServerVO>> => {
  return request({
    url: '/agent/mcp/list',
    method: 'get',
    params: query
  });
};

/** MCP 服务器详情 */
export const getMcpServer = (id: string | number): AxiosPromise<McpServerVO> => {
  return request({
    url: '/agent/mcp/' + id,
    method: 'get'
  });
};

/** 新增 MCP 服务器 */
export const addMcpServer = (data: McpServerForm) => {
  return request({
    url: '/agent/mcp',
    method: 'post',
    data: data
  });
};

/** 修改 MCP 服务器 */
export const updateMcpServer = (data: McpServerForm) => {
  return request({
    url: '/agent/mcp',
    method: 'put',
    data: data
  });
};

/** 启用/停用 MCP 服务器 */
export const changeMcpServerStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/mcp/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除 MCP 服务器 */
export const delMcpServer = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/mcp/' + ids,
    method: 'delete'
  });
};

/** 连接测试:真实建立 MCP 连接并列出工具名 */
export const testMcpServer = (id: string | number): AxiosPromise<string[]> => {
  return request({
    url: `/agent/mcp/${id}/test`,
    method: 'post'
  });
};
