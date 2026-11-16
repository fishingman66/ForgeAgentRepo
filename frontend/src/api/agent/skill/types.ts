/** 技能包视图对象(08方案 / A1) */
export interface SkillVO {
  id: string;
  /** 技能名称(同一空间内唯一) */
  name: string;
  /** 一行摘要(懒加载目录展示用) */
  description: string;
  /** 操作手册(Markdown,详情页返回) */
  instructions?: string;
  /** 引用平台工具ID列表(逗号分隔) */
  toolIds?: string;
  /** 引用MCP服务器ID列表(逗号分隔) */
  mcpIds?: string;
  /** 引用知识库ID列表(逗号分隔) */
  knowledgeIds?: string;
  /** 加载后追加的提示词片段 */
  promptFragment?: string;
  /** 技能版本(内容变更自增) */
  version?: number;
  /** 状态(0正常 1停用) */
  status: string;
  /** 归属工作空间(空=平台共享) */
  workspaceId?: number | string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 技能包表单对象 */
export interface SkillForm {
  id?: string;
  /** 归属工作空间(NULL=平台共享);编辑时不可变 */
  workspaceId?: number | string;
  /** 是否创建为平台共享技能(仅超管可选) */
  shared?: boolean;
  name: string;
  description: string;
  instructions?: string;
  toolIds?: string;
  mcpIds?: string;
  knowledgeIds?: string;
  promptFragment?: string;
  status?: string;
  remark?: string;
}

/** 技能包查询参数 */
export interface SkillQuery extends PageQuery {
  keyword?: string;
  status?: string;
}
