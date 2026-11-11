import type { AuditLogQuery, AuditLogVO } from '@/api/agent/audit/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询审计日志 */
export const listAuditLog = (query: AuditLogQuery): AxiosPromise<PageResult<AuditLogVO>> => {
  return request({
    url: '/agent/audit/list',
    method: 'get',
    params: query
  });
};

/** 审计日志详情 */
export const getAuditLog = (id: string | number): AxiosPromise<AuditLogVO> => {
  return request({
    url: '/agent/audit/' + id,
    method: 'get'
  });
};
