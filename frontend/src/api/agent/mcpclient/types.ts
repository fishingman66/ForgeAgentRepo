/** 对外 MCP 客户端视图对象(08方案 / A5 增强) */
export interface McpClientVO {
  id: string | number;
  /** 客户端名称(如 DSH / Claude Code) */
  name: string;
  /** 令牌前缀(不足以还原) */
  tokenPrefix: string;
  /** 绑定工作空间(NULL=平台全量只读) */
  workspaceId?: number | string;
  /** 授权域,逗号分隔(knowledge,skill);空=全部 */
  scopes?: string;
  /** 0启用 1停用 */
  enabled: string;
  expireTime?: string;
  lastUsedTime?: string;
  remark?: string;
  createTime?: string;
}

/** 新建/编辑表单 */
export interface McpClientForm {
  id?: string | number;
  name: string;
  workspaceId?: number | string;
  /** 逗号分隔;空=全部授权域 */
  scopes?: string;
  /** yyyy-MM-dd HH:mm:ss;空=长期 */
  expireTime?: string;
  remark?: string;
}

/** 令牌下发(创建/轮转时返回一次) */
export interface McpClientTokenVO {
  id: string | number;
  name: string;
  token: string;
  tokenPrefix: string;
}

/** 查询参数 */
export interface McpClientQuery extends PageQuery {
  name?: string;
}
