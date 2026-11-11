/** 长期记忆视图对象 */
export interface MemoryVO {
  id: number | string;
  agentId: number | string;
  workspaceId?: number | string;
  /** 用户ID,NULL 表示共享记忆 */
  userId?: number | string | null;
  content: string;
  sourceSessionId?: number | string | null;
  createTime?: string;
}

/** 记忆查询参数 */
export interface MemoryQuery extends PageQuery {
  agentId: number | string;
  keyword?: string;
}

/** 新增记忆参数 */
export interface MemoryForm {
  agentId: number | string;
  content: string;
}

/** 语义检索命中(P3/M2') */
export interface MemorySearchHit {
  id: number | string;
  agentId: number | string;
  userId?: number | string | null;
  content: string;
  factKey?: string | null;
  importance?: number | null;
  sourceSessionId?: number | string | null;
  createTime?: string;
}

/** 图谱实体节点 */
export interface MemoryGraphNode {
  key: string;
  name: string;
  type: string;
  salience: number;
}

/** 图谱关系边 */
export interface MemoryGraphEdge {
  id?: number | null;
  sourceKey: string;
  sourceName: string;
  relation: string;
  targetKey: string;
  targetName: string;
  weight: number;
}
