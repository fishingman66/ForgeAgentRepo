/** MCP 服务器视图对象 */
export interface McpServerVO {
  id: string;
  name: string;
  description?: string;
  /** 传输类型(sse/streamable_http) */
  serverType: string;
  url: string;
  /** 自定义请求头(JSON) */
  headers?: string;
  timeoutSeconds?: number;
  /** 工具审批模式(auto/ask/deny) */
  approvalMode?: string;
  approvalTimeout?: number;
  /** 最近一次连接测试发现的工具数 */
  toolCount?: number;
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** MCP 服务器表单对象 */
export interface McpServerForm {
  id?: string;
  name: string;
  description?: string;
  serverType: string;
  url: string;
  headers?: string;
  timeoutSeconds?: number;
  approvalMode?: string;
  approvalTimeout?: number;
  status?: string;
  remark?: string;
}

/** MCP 服务器查询参数 */
export interface McpServerQuery extends PageQuery {
  keyword?: string;
  status?: string;
}
