import type { FeedbackQuery, FeedbackVO } from '@/api/agent/feedback/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 反馈分页列表 */
export const listFeedback = (query: FeedbackQuery): AxiosPromise<PageResult<FeedbackVO>> => {
  return request({
    url: '/agent/feedback/page',
    method: 'get',
    params: query
  });
};

/** 反馈导出 Excel */
export const exportFeedback = (query: FeedbackQuery) => {
  return request({
    url: '/agent/feedback/export',
    method: 'get',
    params: query,
    responseType: 'blob'
  });
};
