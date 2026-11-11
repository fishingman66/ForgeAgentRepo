import type { SessionUsageVO, TokenUsageVO, UsageQuery, UsageReportVO } from '@/api/agent/usage/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 用量统计报表(指标卡/趋势/排行) */
export const usageReport = (query: UsageQuery): AxiosPromise<UsageReportVO> => {
  return request({
    url: '/agent/usage/report',
    method: 'get',
    params: query
  });
};

/** 用量明细分页 */
export const listUsage = (query: UsageQuery): AxiosPromise<PageResult<TokenUsageVO>> => {
  return request({
    url: '/agent/usage/list',
    method: 'get',
    params: query
  });
};

/** 会话内消耗汇总 */
export const sessionUsage = (sessionId: string | number): AxiosPromise<SessionUsageVO> => {
  return request({
    url: '/agent/usage/session/' + sessionId,
    method: 'get'
  });
};
