/** Agent 视图对象 */
export interface AgentVO {
  id: number | string;
  workspaceId: number | string;
  workspaceName?: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  welcomeMsg?: string;
  /** 推荐问题(JSON 数组字符串,会话空态快捷提问) */
  suggestedQuestions?: string;
  modelId?: number | string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  tokenBudget?: number;
  outputSchema?: string;
  /** 长期记忆开关:1 开启 / 0 关闭 */
  memoryEnabled?: string;
  /** 绑定模型是否支持图片输入:1 支持 / 0 不支持 */
  supportVision?: string;
  teamMode?: string;
  teamIds?: Array<number | string>;
  toolIds?: Array<number | string>;
  mcpIds?: Array<number | string>;
  knowledgeIds?: Array<number | string>;
  workflowJson?: string;
  /** P2/W10:工作流触发模式 0 关闭 / 1 每轮自动执行 / 2 按意图路由 */
  workflowTriggerMode?: string;
  status: string;
  /** P1:配置版本与发布版本 */
  version?: number;
  publishedVersion?: number;
  configHash?: string;
  /** P1:Prompt 模板绑定 */
  promptTemplateId?: number | string | null;
  promptBindingMode?: string;
  promptVersionId?: number | string | null;
  promptVariables?: string;
  createTime?: string;
  updateTime?: string;
}

/** Agent 表单对象 */
export interface AgentForm {
  id?: number | string;
  workspaceId?: number | string;
  name: string;
  description?: string;
  systemPrompt?: string;
  welcomeMsg?: string;
  /** 推荐问题(JSON 数组字符串,会话空态快捷提问) */
  suggestedQuestions?: string;
  modelId?: number | string | null;
  temperature?: number;
  maxTokens?: number;
  tokenBudget?: number | null;
  outputSchema?: string;
  /** 长期记忆开关(表单为布尔,落库为 1/0) */
  memoryEnabled?: boolean;
  teamMode?: string;
  teamIds?: Array<number | string>;
  toolIds?: Array<number | string>;
  mcpIds?: Array<number | string>;
  knowledgeIds?: Array<number | string>;
  /** P2/W10:工作流触发模式 0 关闭 / 1 每轮自动执行 / 2 按意图路由 */
  workflowTriggerMode?: string;
  /** P1:Prompt 模板绑定(MANUAL / LOCKED / FOLLOW_STABLE) */
  promptTemplateId?: number | string | null;
  promptBindingMode?: string;
  promptVersionId?: number | string | null;
  promptVariables?: string;
  status?: string;
  keyword?: string;
}

/** Agent 查询参数 */
export interface AgentQuery extends PageQuery {
  workspaceId?: number | string;
  keyword?: string;
  status?: string;
  /** 排序字段(如 createTime),后端透传 */
  orderByColumn?: string;
  /** 排序方向:asc / desc */
  isAsc?: string;
}
