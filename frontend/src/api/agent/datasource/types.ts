/** 问数数据源视图对象(不含连接密码) */
export interface DatasourceVO {
  id: string;
  /** 归属空间(NULL=平台共享) */
  workspaceId?: number | string;
  name: string;
  description?: string;
  /** 数据库类型(mysql) */
  dbType: string;
  host: string;
  port: number;
  dbName: string;
  username: string;
  /** 额外 JDBC 参数 */
  params?: string;
  /** 单次查询最大返回行数 */
  maxRows?: number;
  /** 查询超时(秒) */
  timeoutSeconds?: number;
  /** 可查询表白名单(逗号分隔,空为全部) */
  tableWhitelist?: string;
  /** 是否启用语义层注入 */
  semanticEnabled?: string;
  /** 表结构快照字符数(0/空 表示未同步) */
  schemaSize?: number;
  /** 表结构最近同步时间 */
  schemaSyncedAt?: string;
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 问数数据源表单对象 */
export interface DatasourceForm {
  id?: string;
  /** 归属空间(空间私有必填) */
  workspaceId?: number | string;
  /** 是否平台共享(仅超管) */
  shared?: boolean;
  name: string;
  description?: string;
  dbType: string;
  host: string;
  port: number;
  dbName: string;
  username: string;
  /** 编辑时留空表示不修改 */
  password?: string;
  params?: string;
  maxRows?: number;
  timeoutSeconds?: number;
  /** 可查询表白名单(逗号分隔,空为全部) */
  tableWhitelist?: string;
  /** 是否启用语义层注入 */
  semanticEnabled?: string;
  status?: string;
  remark?: string;
}

/** 问数数据源查询参数 */
export interface DatasourceQuery extends PageQuery {
  keyword?: string;
  status?: string;
}

/** 数据源可查询表项 */
export interface DatasourceTableItem {
  name: string;
  comment?: string;
}

/** 问数示例视图对象 */
export interface DatasourceExampleVO {
  id: string;
  datasourceId: string;
  question: string;
  sqlContent: string;
  sort?: number;
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 问数示例表单对象 */
export interface DatasourceExampleForm {
  id?: string;
  datasourceId: string;
  question: string;
  sqlContent: string;
  sort?: number;
  status?: string;
  remark?: string;
}
