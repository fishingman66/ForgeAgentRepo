/** 市场条目视图对象 */
export interface MarketListingVO {
  id: string;
  agentId: string;
  workspaceId: string;
  versionNo: number;
  name: string;
  category: string;
  icon?: string;
  description?: string;
  usageDoc?: string;
  depsJson?: string;
  /** 0待审核 1已上架 2已驳回 3强制下架 4主动下架 */
  status: string;
  reviewReason?: string;
  publisherId: string;
  publisherName?: string;
  installCount: number;
  ratingSum: number;
  ratingCount: number;
  ratingAvg?: number;
  createTime?: string;
  updateTime?: string;
}

/** 市场安装记录视图对象 */
export interface MarketInstallVO {
  id: string;
  listingId: string;
  listingName: string;
  listingIcon?: string;
  workspaceId: string;
  workspaceName?: string;
  agentCopyId: string;
  agentCopyName?: string;
  versionNo: number;
  /** 最新上架版本号(大于 versionNo 则可升级) */
  latestVersionNo?: number;
  rated: boolean;
  userId: string;
  createTime?: string;
}

/** 安装依赖检查项 */
export interface InstallDepItem {
  id: string;
  name: string;
  found: boolean;
  enabled: boolean;
}

/** 安装依赖检查结果 */
export interface InstallCheckVO {
  modelOk: boolean;
  modelName?: string;
  modelMessage?: string;
  tools: InstallDepItem[];
  knowledges: InstallDepItem[];
  installable: boolean;
}

/** 市场查询参数(浏览/我的发布/审核队列共用) */
export interface MarketQuery extends PageQuery {
  keyword?: string;
  category?: string;
  status?: string;
  /** new最新 / install安装量 / rating评分 */
  sort?: string;
}

/** 提交发布表单 */
export interface MarketPublishForm {
  agentId?: string;
  name: string;
  category: string;
  icon?: string;
  description?: string;
  usageDoc?: string;
}

/** 安装表单(检查/确认共用) */
export interface MarketInstallForm {
  listingId: string;
  workspaceId: string;
}

/** 评分表单 */
export interface MarketRateForm {
  installId: string;
  stars: number;
  comment?: string;
}
