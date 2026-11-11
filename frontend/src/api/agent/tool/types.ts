/** 工具视图对象 */
export interface ToolVO {
  id: number | string;
  /** 归属空间(NULL=平台共享) */
  workspaceId?: number | string;
  name: string;
  description?: string;
  toolType: string;
  /** 绑定数据源ID(仅sql类型工具) */
  datasourceId?: number | string;
  httpMethod?: string;
  httpUrl?: string;
  httpHeaders?: string;
  httpParams?: string;
  sandboxImage?: string;
  sandboxTimeout?: number;
  sandboxMemory?: string;
  sandboxCpus?: string;
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 工具表单对象 */
export interface ToolForm {
  id?: number | string;
  /** 归属空间(空间私有必填) */
  workspaceId?: number | string;
  /** 是否平台共享(仅超管) */
  shared?: boolean;
  name: string;
  description?: string;
  toolType: string;
  /** 绑定数据源ID(仅sql类型工具) */
  datasourceId?: number | string;
  httpMethod?: string;
  httpUrl?: string;
  httpHeaders?: string;
  httpParams?: string;
  sandboxImage?: string;
  sandboxTimeout?: number;
  sandboxMemory?: string;
  sandboxCpus?: string;
  status?: string;
  remark?: string;
  keyword?: string;
}

/** 工具查询参数 */
export interface ToolQuery extends PageQuery {
  keyword?: string;
  status?: string;
}
