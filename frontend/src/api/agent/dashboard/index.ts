import type { DashboardOverviewVO } from '@/api/agent/dashboard/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 运营看板概览(按角色裁剪 section) */
export const dashboardOverview = (): AxiosPromise<DashboardOverviewVO> => {
  return request({
    url: '/agent/dashboard/overview',
    method: 'get'
  });
};
