/** MCP 服务器视图对象 */
export interface McpServerVO {
  id: string;
  /** 归属工作空间(空=平台共享,非空=空间私有) */
  workspaceId?: number | string;
  name: string;
  description?: string;
  /** 传输类型(sse/streamable_http/stdio) */
  serverType: string;
  url: string;
  /** C2:stdio 可执行命令 */
  command?: string;
  /** C2:stdio 启动参数(JSON 数组) */
  args?: string;
  /** C2:环境变量值后端不回显,恒为 null */
  env?: string;
  /** C2:环境变量键名列表(仅键名) */
  envKeys?: string[];
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
  /** 归属工作空间(NULL=平台共享,非空=空间私有);编辑时归属不可变 */
  workspaceId?: number | string;
  /** 是否创建为平台共享资源(仅超管可选) */
  shared?: boolean;
  name: string;
  description?: string;
  serverType: string;
  url: string;
  headers?: string;
  /** C2:stdio 可执行命令(须在服务端白名单内) */
  command?: string;
  /** C2:stdio 启动参数(JSON 数组) */
  args?: string;
  /** C2:stdio 环境变量(JSON 对象);编辑时留空表示沿用库内既有值 */
  env?: string;
  /** C2:环境变量键名(后端回显,仅用于占位提示,提交时剔除) */
  envKeys?: string[];
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
