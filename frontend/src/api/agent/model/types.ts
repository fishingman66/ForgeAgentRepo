/** 模型配置视图对象 */
export interface ModelVO {
  id: number | string;
  /** 归属空间(NULL=平台共享) */
  workspaceId?: number | string;
  name: string;
  provider: string;
  baseUrl?: string;
  apiKey?: string;
  modelName: string;
  /** 支持图片输入:1 支持 / 0 不支持 */
  supportVision?: string;
  temperature?: number;
  maxTokens?: number;
  /** 上下文窗口(tokens) */
  contextWindow?: number | null;
  /** 输入单价(每百万token) */
  priceInput?: number | null;
  /** 输出单价(每百万token) */
  priceOutput?: number | null;
  /** 定价币种 */
  priceCurrency?: string;
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 模型配置表单对象 */
export interface ModelForm {
  id?: number | string;
  /** 归属空间(空间私有必填) */
  workspaceId?: number | string;
  /** 是否平台共享(仅超管) */
  shared?: boolean;
  name: string;
  provider: string;
  baseUrl?: string;
  apiKey?: string;
  modelName: string;
  /** 支持图片输入:1 支持 / 0 不支持 */
  supportVision?: string;
  temperature?: number;
  maxTokens?: number;
  /** 上下文窗口(tokens,留空不展示会话上下文剩余) */
  contextWindow?: number | null;
  /** 输入单价(每百万token,非必填) */
  priceInput?: number | null;
  /** 输出单价(每百万token,非必填) */
  priceOutput?: number | null;
  /** 定价币种(默认 USD) */
  priceCurrency?: string;
  status?: string;
  remark?: string;
  keyword?: string;
}

/** 模型配置查询参数 */
export interface ModelQuery extends PageQuery {
  keyword?: string;
  status?: string;
}
