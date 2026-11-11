import type { ApprovalQuery, ApprovalVO } from '@/api/agent/approval/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询审批单 */
export const listApproval = (query: ApprovalQuery): AxiosPromise<PageResult<ApprovalVO>> => {
  return request({
    url: '/agent/approval/list',
    method: 'get',
    params: query
  });
};

/** 审批通过 */
export const approveApproval = (id: string | number, note?: string) => {
  return request({
    url: `/agent/approval/${id}/approve`,
    method: 'post',
    params: { note }
  });
};

/** 审批拒绝 */
export const rejectApproval = (id: string | number, note?: string) => {
  return request({
    url: `/agent/approval/${id}/reject`,
    method: 'post',
    params: { note }
  });
};
