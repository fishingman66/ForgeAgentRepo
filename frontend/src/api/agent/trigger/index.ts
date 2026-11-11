import type { TriggerForm, TriggerLogVO, TriggerQuery, TriggerRunVO, TriggerVO } from '@/api/agent/trigger/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询触发器 */
export const listTrigger = (query: TriggerQuery): AxiosPromise<PageResult<TriggerVO>> => {
  return request({
    url: '/agent/trigger/list',
    method: 'get',
    params: query
  });
};

/** 触发器详情 */
export const getTrigger = (id: string | number): AxiosPromise<TriggerVO> => {
  return request({
    url: '/agent/trigger/' + id,
    method: 'get'
  });
};

/** 新增触发器 */
export const addTrigger = (data: TriggerForm): AxiosPromise<void> => {
  return request({
    url: '/agent/trigger',
    method: 'post',
    data: data
  });
};

/** 修改触发器 */
export const updateTrigger = (data: TriggerForm): AxiosPromise<void> => {
  return request({
    url: '/agent/trigger',
    method: 'put',
    data: data
  });
};

/** 启用/停用触发器 */
export const changeTriggerEnabled = (id: string | number, enabled: string): AxiosPromise<void> => {
  return request({
    url: '/agent/trigger/enabled',
    method: 'put',
    params: { id, enabled }
  });
};

/** 删除触发器 */
export const delTrigger = (ids: string | number | Array<string | number>): AxiosPromise<void> => {
  return request({
    url: '/agent/trigger/' + ids,
    method: 'delete'
  });
};

/** 手动立即执行一次 */
export const runTrigger = (id: string | number): AxiosPromise<TriggerRunVO> => {
  return request({
    url: '/agent/trigger/run/' + id,
    method: 'post'
  });
};

/** 分页查询触发器执行记录 */
export const listTriggerLog = (triggerId: string | number, query: PageQuery): AxiosPromise<PageResult<TriggerLogVO>> => {
  return request({
    url: '/agent/trigger/log/list',
    method: 'get',
    params: { triggerId, ...query }
  });
};
