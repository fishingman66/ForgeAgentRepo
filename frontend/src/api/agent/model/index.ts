import type { ModelForm, ModelQuery, ModelVO } from '@/api/agent/model/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询模型配置 */
export const listModel = (query: ModelQuery): AxiosPromise<PageResult<ModelVO>> => {
  return request({
    url: '/agent/model/list',
    method: 'get',
    params: query
  });
};

/** 模型配置详情 */
export const getModel = (id: string | number): AxiosPromise<ModelVO> => {
  return request({
    url: '/agent/model/' + id,
    method: 'get'
  });
};

/** 新增模型配置 */
export const addModel = (data: ModelForm) => {
  return request({
    url: '/agent/model',
    method: 'post',
    data: data
  });
};

/** 修改模型配置 */
export const updateModel = (data: ModelForm) => {
  return request({
    url: '/agent/model',
    method: 'put',
    data: data
  });
};

/** 启用/停用模型 */
export const changeModelStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/model/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除模型配置 */
export const delModel = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/model/' + ids,
    method: 'delete'
  });
};
