import type { ToolForm, ToolQuery, ToolVO } from '@/api/agent/tool/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询工具 */
export const listTool = (query: ToolQuery): AxiosPromise<PageResult<ToolVO>> => {
  return request({
    url: '/agent/tool/list',
    method: 'get',
    params: query
  });
};

/** 工具详情 */
export const getTool = (id: string | number): AxiosPromise<ToolVO> => {
  return request({
    url: '/agent/tool/' + id,
    method: 'get'
  });
};

/** 新增工具 */
export const addTool = (data: ToolForm) => {
  return request({
    url: '/agent/tool',
    method: 'post',
    data: data
  });
};

/** 修改工具 */
export const updateTool = (data: ToolForm) => {
  return request({
    url: '/agent/tool',
    method: 'put',
    data: data
  });
};

/** 启用/停用工具 */
export const changeToolStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/tool/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除工具 */
export const delTool = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/tool/' + ids,
    method: 'delete'
  });
};
