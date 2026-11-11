/** 知识库视图对象 */
export interface KnowledgeVO {
  id: number | string;
  /** 所属工作空间ID(NULL表示全局/历史数据) */
  workspaceId?: number | string | null;
  name: string;
  description?: string;
  status: string;
  embeddingModel?: string;
  /** 重排序模型名称(agent_model 配置名称) */
  rerankModel?: string;
  /** 相关度阈值(0~1,作用于向量相似度,为空不过滤) */
  scoreThreshold?: number | string | null;
  /** 精排相关度阈值(0~1,作用于 rerankScore;为空不过滤精排结果) */
  rerankThreshold?: number | string | null;
  /** 父子分块开关(1=先切父块再切子块,检索命中子块注入父块全文) */
  parentChunkEnabled?: string;
  /** 父块目标大小(字符) */
  parentChunkSize?: number;
  vectorBackend?: string;
  vectorDim?: number;
  chunkSize?: number;
  chunkOverlap?: number;
  /** 分块计价单位(char字符/token估算) */
  chunkUnit?: string;
  /** 分块策略(structure结构感知/fixed定长对照) */
  splitStrategy?: string;
  docCount?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 知识库表单对象 */
export interface KnowledgeForm {
  id?: number | string;
  /** 所属工作空间ID(新增必填;留空表示全局,仅超管可创建) */
  workspaceId?: number | string | null;
  name: string;
  description?: string;
  status?: string;
  embeddingModel?: string;
  /** 重排序模型名称(agent_model 配置名称,可选) */
  rerankModel?: string;
  /** 相关度阈值(0~1,作用于向量相似度,为空不过滤) */
  scoreThreshold?: number | null;
  /** 精排相关度阈值(0~1,作用于 rerankScore;为空不过滤精排结果) */
  rerankThreshold?: number | null;
  /** 父子分块开关(1=先切父块再切子块) */
  parentChunkEnabled?: string;
  /** 父块目标大小(字符) */
  parentChunkSize?: number;
  chunkSize?: number;
  chunkOverlap?: number;
  /** 分块计价单位(char字符/token估算),留空按 char */
  chunkUnit?: string;
  /** 分块策略(structure结构感知/fixed定长对照),留空按 structure */
  splitStrategy?: string;
  remark?: string;
  keyword?: string;
}

/** 知识库查询参数 */
export interface KnowledgeQuery extends PageQuery {
  keyword?: string;
  status?: string;
  /** 工作空间ID(不传时按当前用户可见范围查询) */
  workspaceId?: number | string | null;
}

/** 知识库文档视图对象 */
export interface KnowledgeDocumentVO {
  id: number | string;
  knowledgeId: number | string;
  fileName: string;
  fileExt: string;
  fileSize: number;
  status: string;
  failReason?: string;
  chunkCount: number;
  createTime?: string;
  updateTime?: string;
}

/** 知识库文档查询参数 */
export interface KnowledgeDocumentQuery extends PageQuery {
  knowledgeId: number | string;
}

/** 知识库分块视图对象 */
export interface KnowledgeChunkVO {
  id: number | string;
  knowledgeId: number | string;
  documentId: number | string;
  chunkIndex: number;
  content: string;
  /** 是否启用(1启用 0禁用,禁用不参与检索) */
  enabled?: number;
  /** 估算 token 数 */
  tokenCount?: number;
  /** 累计检索命中次数 */
  hitCount?: number;
  /** 来源定位标记(如 page=3/sheet=销售表) */
  pageLabel?: string;
  /** 分块角色(c=检索子块 p=父块上下文块,父块不参与检索) */
  chunkRole?: string;
  /** 父块ID(chunk_role=c 时指向所属父块) */
  parentId?: number | string;
  createTime?: string;
}

/** 知识库检索请求 */
export interface KnowledgeSearchForm {
  knowledgeId: number | string;
  query: string;
  topK?: number;
  /** 检索模式:hybrid(默认)/vector/keyword */
  searchMode?: string;
  /** 是否开启重排序精排(需知识库已配置重排序模型) */
  rerank?: boolean;
}

/** 知识库检索命中 */
export interface KnowledgeSearchHitVO {
  chunkId: number | string;
  documentId: number | string;
  fileName: string;
  chunkIndex: number;
  content: string;
  score: number;
  /** 向量通道得分(COSINE 相似度,0~1) */
  vectorScore?: number;
  /** 关键词通道得分(MySQL 全文相关度,非归一化) */
  keywordScore?: number;
  /** RRF 融合得分 */
  rrfScore?: number;
  /** 命中通道:vector/keyword/both */
  matchChannels?: string;
  /** 分块是否启用(1启用 0禁用) */
  enabled?: number;
  /** 重排序精排得分(开启 rerank 时由模型打分产生) */
  rerankScore?: number;
  /** 来源定位标记(如 page=3/sheet=销售表) */
  pageLabel?: string;
  /** 父块全文(开启父子分块的知识库返回,注入上下文时优先使用) */
  parentContent?: string;
}

/** 知识库零命中查询日志 */
export interface KnowledgeZeroHitVO {
  id: number | string;
  agentId?: number | string;
  agentName?: string;
  sessionId?: number | string;
  userId?: number | string;
  /** 用户原始问题 */
  originalQuery?: string;
  /** 改写后的检索查询 */
  rewrittenQuery?: string;
  /** 参与检索的知识库ID(逗号分隔) */
  knowledgeIds?: string;
  createTime?: string;
}

/** 零命中查询参数 */
export interface KnowledgeZeroHitQuery extends PageQuery {
  keyword?: string;
}

/** 知识库检索评测用例 */
export interface KbEvalCaseVO {
  id?: number | string;
  knowledgeId: number | string;
  /** 评测问题 */
  question: string;
  /** 期望命中的文档名(模糊包含,空=不校验) */
  expectedDocName?: string;
  /** 期望命中内容包含的关键词(逗号分隔,任一命中即可,空=不校验) */
  expectedKeywords?: string;
  remark?: string;
  createTime?: string;
}

/** 知识库检索评测运行记录 */
export interface KbEvalRunVO {
  id: number | string;
  knowledgeId: number | string;
  /** 评测时的召回条数 */
  topK?: number;
  totalCases?: number;
  hitCases?: number;
  /** 平均倒数排名(MRR,0~1) */
  mrr?: number;
  /** 命中用例的平均相关度分 */
  avgScore?: number;
  durationMs?: number;
  createTime?: string;
}

/** 知识库索引重建进度 */
export interface KbRebuildProgressVO {
  knowledgeId: number | string;
  /** 文档总数 */
  total?: number;
  /** 待处理数 */
  pending?: number;
  /** 已完成数 */
  success?: number;
  /** 失败数 */
  failed?: number;
  /** 完成百分比(0-100) */
  percent?: number;
}
