import type {
  InstallCheckVO,
  MarketInstallForm,
  MarketInstallVO,
  MarketListingVO,
  MarketPublishForm,
  MarketQuery,
  MarketRateForm
} from '@/api/agent/market/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 市场浏览(仅已上架) */
export const browseMarket = (query: MarketQuery): AxiosPromise<PageResult<MarketListingVO>> => {
  return request({
    url: '/agent/market/list',
    method: 'get',
    params: query
  });
};

/** 市场条目详情 */
export const getMarketListing = (id: string | number): AxiosPromise<MarketListingVO> => {
  return request({
    url: '/agent/market/' + id,
    method: 'get'
  });
};

/** 安装依赖检查 */
export const installCheck = (data: MarketInstallForm): AxiosPromise<InstallCheckVO> => {
  return request({
    url: '/agent/market/install/check',
    method: 'post',
    data: data
  });
};

/** 确认安装(生成 Agent 副本) */
export const installConfirm = (data: MarketInstallForm): AxiosPromise<string | number> => {
  return request({
    url: '/agent/market/install/confirm',
    method: 'post',
    data: data
  });
};

/** 我的安装记录(含可升级标记) */
export const myInstalls = (workspaceId?: string | number): AxiosPromise<MarketInstallVO[]> => {
  return request({
    url: '/agent/market/install/list',
    method: 'get',
    params: { workspaceId }
  });
};

/** 升级到最新版本 */
export const upgradeInstall = (installId: string | number) => {
  return request({
    url: '/agent/market/upgrade/' + installId,
    method: 'put'
  });
};

/** 评分(仅安装人,可改评) */
export const rateInstall = (data: MarketRateForm) => {
  return request({
    url: '/agent/market/rate',
    method: 'post',
    data: data
  });
};

/** 我的发布分页 */
export const myPublishList = (query: MarketQuery): AxiosPromise<PageResult<MarketListingVO>> => {
  return request({
    url: '/agent/market/my/list',
    method: 'get',
    params: query
  });
};

/** 提交发布(仅本人已发布的 Agent) */
export const publishListing = (data: MarketPublishForm): AxiosPromise<string | number> => {
  return request({
    url: '/agent/market/my/publish',
    method: 'post',
    data: data
  });
};

/** 主动下架 */
export const offlineListing = (id: string | number) => {
  return request({
    url: '/agent/market/my/offline/' + id,
    method: 'put'
  });
};

/** 审核队列分页(默认待审核) */
export const reviewList = (query: MarketQuery): AxiosPromise<PageResult<MarketListingVO>> => {
  return request({
    url: '/agent/market/audit/list',
    method: 'get',
    params: query
  });
};

/** 审核通过 */
export const approveListing = (id: string | number) => {
  return request({
    url: '/agent/market/audit/approve/' + id,
    method: 'put'
  });
};

/** 审核驳回 */
export const rejectListing = (id: string | number, reason: string) => {
  return request({
    url: '/agent/market/audit/reject/' + id,
    method: 'put',
    params: { reason }
  });
};

/** 强制下架 */
export const forceOffListing = (id: string | number, reason?: string) => {
  return request({
    url: '/agent/market/audit/forceoff/' + id,
    method: 'put',
    params: { reason }
  });
};
