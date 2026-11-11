/** 空间 API Key 视图对象(列表脱敏) */
export interface ApiKeyVO {
  id: string;
  workspaceId: string;
  workspaceName?: string;
  name: string;
  maskedKey: string;
  /** 0正常 1已吊销 */
  status: string;
  lastUsedTime?: string;
  createTime?: string;
  createBy?: string;
}

/** 创建 Key 返回(明文仅此一次) */
export interface ApiKeyCreateVO {
  id: string;
  plainKey: string;
}

/** API Key 表单对象 */
export interface ApiKeyForm {
  workspaceId?: string;
  name: string;
}

/** API Key 查询参数 */
export interface ApiKeyQuery extends PageQuery {
  workspaceId?: string;
  name?: string;
  status?: string;
}
