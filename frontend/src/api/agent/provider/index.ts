import type { ProviderForm, ProviderQuery, ProviderVO } from '@/api/agent/provider/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询供应商 */
export const listProvider = (query: ProviderQuery): AxiosPromise<PageResult<ProviderVO>> => {
  return request({
    url: '/agent/provider/list',
    method: 'get',
    params: query
  });
};

/** 全部供应商(下拉用) */
export const allProvider = (): AxiosPromise<ProviderVO[]> => {
  return request({
    url: '/agent/provider/all',
    method: 'get'
  });
};

/** 供应商详情 */
export const getProvider = (id: string | number): AxiosPromise<ProviderVO> => {
  return request({
    url: '/agent/provider/' + id,
    method: 'get'
  });
};

/** 新增供应商 */
export const addProvider = (data: ProviderForm) => {
  return request({
    url: '/agent/provider',
    method: 'post',
    data: data
  });
};

/** 修改供应商 */
export const updateProvider = (data: ProviderForm) => {
  return request({
    url: '/agent/provider',
    method: 'put',
    data: data
  });
};

/** 启用/停用供应商 */
export const changeProviderStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/provider/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除供应商 */
export const delProvider = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/provider/' + ids,
    method: 'delete'
  });
};
