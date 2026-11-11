import type { ApiKeyCreateVO, ApiKeyForm, ApiKeyQuery, ApiKeyVO } from '@/api/agent/apikey/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询 API Key(列表脱敏) */
export const listApiKey = (query: ApiKeyQuery): AxiosPromise<PageResult<ApiKeyVO>> => {
  return request({
    url: '/agent/apikey/list',
    method: 'get',
    params: query
  });
};

/** 创建 API Key(明文仅此一次返回) */
export const addApiKey = (data: ApiKeyForm): AxiosPromise<ApiKeyCreateVO> => {
  return request({
    url: '/agent/apikey',
    method: 'post',
    data: data
  });
};

/** 吊销 API Key */
export const revokeApiKey = (id: string | number) => {
  return request({
    url: '/agent/apikey/revoke/' + id,
    method: 'put'
  });
};
