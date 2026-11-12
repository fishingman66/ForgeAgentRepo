<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="工作空间">
          <el-select v-model="queryParam.workspaceId" placeholder="全部空间" clearable filterable style="width: 180px" @change="handleQuery">
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="知识库名称">
          <el-input v-model="queryParam.keyword" placeholder="请输入名称关键字" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParam.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="正常" value="0" />
            <el-option label="停用" value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel" style="margin-top: 10px">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>知识库</h3>
            <span class="heading-tip">支持 Word/Excel/PDF/TXT/Markdown 文档上传、向量化与检索</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:knowledge:query']" plain icon="DataAnalysis" @click="handleEval">检索评测</el-button>
            <el-button v-hasPermi="['agent:knowledge:list']" plain icon="Warning" @click="handleZeroHit">零命中查询</el-button>
            <el-button v-hasPermi="['agent:knowledge:add']" type="primary" plain icon="Plus" @click="handleAdd">新建知识库</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="knowledgeList" class="data-table">
        <el-table-column label="ID" prop="id" width="70" align="center" />
        <el-table-column label="名称" prop="name" min-width="120" show-overflow-tooltip />
        <el-table-column label="工作空间" min-width="120" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.workspaceId" size="small">{{ workspaceName(scope.row.workspaceId) }}</el-tag>
            <el-tag v-else size="small" type="info">全局</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="描述" prop="description" min-width="160" show-overflow-tooltip />
        <el-table-column label="嵌入模型" prop="embeddingModel" width="120" align="center" />
        <el-table-column label="文档数" width="100" align="center">
          <template #default="scope">
            <span>{{ scope.row.docCount }}</span>
            <el-tooltip
              v-if="(scope.row as KnowledgeVO).staleCount && (scope.row as KnowledgeVO).staleCount! > 0"
              :content="`${(scope.row as KnowledgeVO).staleCount} 篇文档的配置已过期,点「重建」做差异重建`"
              placement="top"
            >
              <el-tag size="small" type="warning" style="margin-left: 4px">{{ (scope.row as KnowledgeVO).staleCount }} 待重建</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as KnowledgeVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="165" align="center" />
        <el-table-column label="操作" width="270" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:knowledgeDoc:list']" size="small" text type="primary" icon="Document" @click="handleDocs(scope.row as KnowledgeVO)">文档</el-button>
            <el-button v-hasPermi="['agent:knowledge:search']" size="small" text type="success" icon="Search" @click="handleSearch(scope.row as KnowledgeVO)">检索</el-button>
            <el-button v-hasPermi="['agent:knowledge:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as KnowledgeVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:knowledge:edit']" size="small" text type="warning" icon="RefreshRight" @click="handleRebuild(scope.row as KnowledgeVO)">重建</el-button>
            <el-button v-hasPermi="['agent:knowledge:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as KnowledgeVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新建/编辑知识库 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="工作空间" prop="workspaceId">
          <el-select v-model="form.workspaceId" placeholder="请选择工作空间" :disabled="isEdit" filterable style="width: 100%" clearable>
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <div class="form-tip">不选则创建全局知识库(仅超级管理员可管理)</div>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,全局唯一" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="嵌入模型" prop="embeddingModel">
          <el-input v-model="form.embeddingModel" placeholder="local-hash(内置)或模型配置名称" maxlength="100" />
          <div class="form-tip">内置 local-hash 为演示模型;生产可切换为 OpenAI 兼容嵌入模型配置名称</div>
        </el-form-item>
        <el-form-item label="重排序模型" prop="rerankModel">
          <el-input v-model="form.rerankModel" placeholder="可选,填模型配置名称后可在检索时开启精排" maxlength="100" />
        </el-form-item>
        <el-form-item label="相似度阈值" prop="scoreThreshold">
          <el-input-number v-model="form.scoreThreshold" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" style="width: 180px" placeholder="留空不过滤" />
          <div class="form-tip">作用于向量相似度(0~1),未开启精排时低于阈值的分块不召回;留空不过滤</div>
        </el-form-item>
        <el-form-item label="精排阈值" prop="rerankThreshold">
          <el-input-number v-model="form.rerankThreshold" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" style="width: 180px" placeholder="留空不过滤" />
          <div class="form-tip">作用于精排分(0~1);留空则精排结果不做过滤</div>
        </el-form-item>
        <el-form-item label="零召回兜底" prop="fallbackAnswerEnabled">
          <el-switch v-model="form.fallbackAnswerEnabled" :active-value="1" :inactive-value="0" />
          <div class="form-tip">开启:检索零命中时允许模型基于通用知识回答,但须声明不来自知识库;关闭:严格模式,零命中一律拒答(金融/医疗场景)</div>
        </el-form-item>
        <el-form-item label="分块单位" prop="chunkUnit">
          <el-select v-model="form.chunkUnit" style="width: 180px">
            <el-option label="按字符(存量口径)" value="char" />
            <el-option label="按 token(跨语种更稳)" value="token" />
          </el-select>
          <div class="form-tip">决定下方"分块大小"的数字按字符还是按估算 token 计;切换后需重建索引生效</div>
        </el-form-item>
        <el-form-item label="分块策略" prop="splitStrategy">
          <el-select v-model="form.splitStrategy" style="width: 180px">
            <el-option label="结构感知(推荐)" value="structure" />
            <el-option label="定长对照(不保护表格/代码)" value="fixed" />
          </el-select>
          <div class="form-tip">结构感知保留表格表头、代码原子块与章节路径;定长仅按段落贪心装填,用于对照</div>
        </el-form-item>
        <el-form-item label="解析清洗" prop="cleanHeaderFooter">
          <div class="clean-switches">
            <el-switch v-model="form.cleanHeaderFooter" active-text="页眉页脚" />
            <el-switch v-model="form.cleanBlankNormalize" active-text="空白归一" />
            <el-switch v-model="form.cleanNfkc" active-text="全半角归一" />
            <el-switch v-model="form.cleanPiiMask" active-text="PII 脱敏" />
          </div>
          <div class="form-tip">按页标记检测重复页眉页脚与纯页码行;空白归一压缩连续空行与行尾空格。全半角归一会把全角逗号/括号一并半角化,中文语料慎用;PII 脱敏对手机/身份证/银行卡/邮箱保留前 3 后 4。改动后需重建索引生效</div>
        </el-form-item>
        <el-form-item label="分块大小" prop="chunkSize">
          <el-input-number v-model="form.chunkSize" :min="100" :max="2000" :step="50" controls-position="right" style="width: 180px" />
          <span class="form-tip ml-2">{{ form.chunkUnit === 'token' ? 'token' : '字符' }}</span>
        </el-form-item>
        <el-form-item label="分块重叠" prop="chunkOverlap">
          <el-input-number v-model="form.chunkOverlap" :min="0" :max="500" :step="10" controls-position="right" style="width: 180px" />
          <span class="form-tip ml-2">{{ form.chunkUnit === 'token' ? 'token' : '字符' }}</span>
        </el-form-item>
        <el-form-item label="父子分块" prop="parentChunkEnabled">
          <el-switch
            :model-value="form.parentChunkEnabled === '1'"
            @change="(val: string | number | boolean) => (form.parentChunkEnabled = val ? '1' : '0')"
          />
          <div class="form-tip">开启后先按父块大小切父块,再在父块内切子块;检索命中子块,注入父块全文(需重建索引生效)</div>
        </el-form-item>
        <el-form-item v-if="form.parentChunkEnabled === '1'" label="父块大小" prop="parentChunkSize">
          <el-input-number v-model="form.parentChunkSize" :min="500" :max="8000" :step="100" controls-position="right" style="width: 180px" />
          <span class="form-tip ml-2">{{ form.chunkUnit === 'token' ? 'token' : '字符' }}</span>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 文档管理 -->
    <el-dialog v-model="docDialog.visible" :title="docDialog.title" width="900px" destroy-on-close>
      <div class="doc-toolbar">
        <el-upload
          :show-file-list="false"
          :http-request="(options: any) => handleUpload(options)"
          accept=".txt,.md,.docx,.xlsx,.pdf"
        >
          <el-button v-hasPermi="['agent:knowledgeDoc:upload']" type="primary" icon="Upload" :loading="docDialog.uploading">上传文档</el-button>
        </el-upload>
        <el-button icon="Refresh" @click="loadDocs">刷新</el-button>
      </div>
      <el-table v-loading="docDialog.loading" border :data="docDialog.list" max-height="420">
        <el-table-column label="ID" prop="id" width="70" align="center" />
        <el-table-column label="文件名" prop="fileName" min-width="180" show-overflow-tooltip />
        <el-table-column label="格式" prop="fileExt" width="70" align="center" />
        <el-table-column label="大小" width="90" align="center">
          <template #default="scope">{{ formatSize(scope.row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.status === '1' ? 'success' : scope.row.status === '2' ? 'danger' : 'warning'">
              {{ scope.row.status === '1' ? '可用' : scope.row.status === '2' ? '失败' : '处理中' }}
            </el-tag>
            <el-tag v-if="(scope.row as KnowledgeDocumentVO).configStale === 1" type="warning" style="margin-left: 4px">待重建</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分块数" prop="chunkCount" width="80" align="center" />
        <el-table-column label="失败原因" prop="failReason" min-width="140" show-overflow-tooltip />
        <el-table-column label="创建时间" prop="createTime" width="165" align="center" />
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="scope">
            <el-button v-if="scope.row.status === '2'" v-hasPermi="['agent:knowledgeDoc:retry']" size="small" text type="warning" icon="Refresh" @click="handleRetry((scope.row as KnowledgeDocumentVO))">重试</el-button>
            <el-button v-hasPermi="['agent:knowledgeDoc:query']" size="small" text type="info" icon="View" @click="handleChunks((scope.row as KnowledgeDocumentVO))">分块</el-button>
            <el-button v-hasPermi="['agent:knowledgeDoc:query']" size="small" text type="primary" icon="Download" @click="handleDownload((scope.row as KnowledgeDocumentVO))">下载</el-button>
            <el-button v-hasPermi="['agent:knowledgeDoc:remove']" size="small" text type="danger" icon="Delete" @click="handleDocDelete((scope.row as KnowledgeDocumentVO))">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="docDialog.query.pageNum" v-model:limit="docDialog.query.pageSize" :total="docDialog.total" @pagination="loadDocs" />
    </el-dialog>

    <!-- 分块查看与管理 -->
    <el-dialog v-model="chunkDialog.visible" :title="chunkDialog.title" width="960px" destroy-on-close>
      <el-table v-loading="chunkDialog.loading" border :data="chunkDialog.list" max-height="480">
        <el-table-column label="序号" prop="chunkIndex" width="60" align="center" />
        <el-table-column label="内容" prop="content" min-width="380" show-overflow-tooltip />
        <el-table-column label="Tokens" prop="tokenCount" width="80" align="center" />
        <el-table-column label="命中" prop="hitCount" width="70" align="center" />
        <el-table-column label="角色" width="70" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.chunkRole === 'p'" size="small" type="info">父块</el-tag>
            <el-tag v-else size="small" type="success">子块</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="定位" width="120" align="center">
          <template #default="scope">
            <span v-if="scope.row.pageLabel">{{ scope.row.pageLabel }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.enabled === 1"
              :disabled="!hasChunkPerm"
              @change="(val: string | number | boolean) => handleChunkEnabled(scope.row as KnowledgeChunkVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="时间" prop="createTime" width="165" align="center" />
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:knowledgeDoc:chunk']" size="small" text type="primary" icon="Edit" @click="handleChunkEdit(scope.row as KnowledgeChunkVO)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 分块编辑 -->
    <el-dialog v-model="chunkEditDialog.visible" title="编辑分块" width="640px" destroy-on-close append-to-body>
      <el-input v-model="chunkEditDialog.content" type="textarea" :rows="10" maxlength="2000" show-word-limit placeholder="分块内容(保存后自动重新向量化)" />
      <template #footer>
        <el-button @click="chunkEditDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="chunkEditDialog.saving" @click="submitChunkEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 检索测试 -->
    <el-dialog v-model="searchDialog.visible" :title="searchDialog.title" width="780px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="查询内容">
          <el-input v-model="searchDialog.query" type="textarea" :rows="3" placeholder="请输入要检索的问题" maxlength="1000" show-word-limit />
        </el-form-item>
        <el-form-item label="TopK">
          <el-slider v-model="searchDialog.topK" :min="1" :max="20" style="width: 240px" />
        </el-form-item>
        <el-form-item label="检索模式">
          <el-radio-group v-model="searchDialog.searchMode">
            <el-radio value="hybrid">混合检索</el-radio>
            <el-radio value="vector">向量检索</el-radio>
            <el-radio value="keyword">关键词检索</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="重排序">
          <el-switch v-model="searchDialog.rerank" />
          <span class="rerank-tip">开启后先在粗排结果中放大召回,再由重排序模型打分取 TopK(需知识库已配置重排序模型)</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" :loading="searchDialog.loading" @click="runSearch">检索</el-button>
          <el-button icon="Refresh" @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>
      <el-divider v-if="searchDialog.results.length > 0">检索结果</el-divider>
      <div v-for="(hit, idx) in searchDialog.results" :key="hit.chunkId" class="hit-card">
        <div class="hit-head">
          <span class="hit-rank">#{{ idx + 1 }}</span>
          <el-tag v-if="hit.matchChannels === 'both'" size="small" type="success">双通道</el-tag>
          <el-tag v-else-if="hit.matchChannels === 'vector'" size="small" type="primary">向量</el-tag>
          <el-tag v-else-if="hit.matchChannels === 'keyword'" size="small" type="warning">关键词</el-tag>
          <el-tag v-if="hit.enabled === 0" size="small" type="danger">已禁用</el-tag>
          <span v-if="hit.vectorScore !== undefined && hit.vectorScore !== null" class="hit-score">
            向量 {{ hit.vectorScore.toFixed(4) }}
          </span>
          <span v-if="hit.keywordScore !== undefined && hit.keywordScore !== null" class="hit-score hit-keyword">
            关键词 {{ hit.keywordScore.toFixed(2) }}
          </span>
          <span class="hit-score hit-rrf">RRF {{ (hit.rrfScore ?? hit.score).toFixed(4) }}</span>
          <span v-if="hit.rerankScore !== undefined && hit.rerankScore !== null" class="hit-score hit-rerank">
            精排 {{ hit.rerankScore.toFixed(2) }}
          </span>
          <el-tag v-if="hit.pageLabel" size="small" type="info">{{ hit.pageLabel }}</el-tag>
          <el-tag v-if="hit.parentContent" size="small" type="warning">父子分块</el-tag>
          <span class="hit-source">{{ hit.fileName }} · 分块 {{ hit.chunkIndex }}</span>
        </div>
        <div class="hit-content">{{ hit.content }}</div>
      </div>
      <el-empty v-if="searchDialog.searched && searchDialog.results.length === 0" description="未命中相关分块" />
    </el-dialog>

    <!-- 检索评测 -->
    <el-dialog v-model="evalDialog.visible" title="检索评测 · 命中率与 MRR" width="1020px" destroy-on-close>
      <el-form :inline="true" class="doc-toolbar">
        <el-form-item label="知识库">
          <el-select v-model="evalDialog.knowledgeId" filterable style="width: 220px" @change="loadEvalAll">
            <el-option v-for="k in knowledgeList" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="evalDialog.topK" :min="1" :max="20" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="VideoPlay" :loading="evalDialog.running" @click="handleRunEval">运行评测</el-button>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">评测用例({{ evalDialog.cases.length }})</el-divider>
      <div class="doc-toolbar">
        <el-input v-model="evalDialog.newCase.question" placeholder="评测问题" style="width: 300px" />
        <el-input v-model="evalDialog.newCase.expectedDocName" placeholder="期望文档名(可选,legacy 口径)" style="width: 200px" />
        <el-input v-model="evalDialog.newCase.expectedKeywords" placeholder="期望关键词(可选,legacy 口径)" style="width: 220px" />
        <el-button type="primary" plain icon="Plus" :loading="evalDialog.saving" @click="handleAddEvalCase">添加用例</el-button>
      </div>
      <div class="doc-toolbar">
        <el-input v-model="evalDialog.newCase.evidences" type="textarea" :rows="2" style="width: 100%"
          placeholder='证据标注 JSON(可选,新口径,填了优先于文档名/关键词):[{"documentId":123,"span":"原文片段"}] —— span 非空走覆盖判定(阈值 0.5,跨块区间并集),span 全空只比文档ID' />
      </div>
      <el-table v-loading="evalDialog.loading" border :data="evalDialog.cases" max-height="220" size="small">
        <el-table-column label="评测问题" prop="question" min-width="240" show-overflow-tooltip />
        <el-table-column label="口径" width="80" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.evidences ? 'primary' : 'info'" size="small">{{ scope.row.evidences ? '证据' : 'legacy' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="期望文档/关键词" min-width="150" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.expectedDocName || scope.row.expectedKeywords || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : scope.row.status === 'DEPRECATED' ? 'info' : 'warning'" size="small">
              {{ scope.row.status === 'ACTIVE' ? '生效' : scope.row.status === 'DEPRECATED' ? '已废弃' : '证据过期' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="scope">
            <el-button v-hasPermi="['agent:knowledge:edit']" size="small" text type="danger" icon="Delete" @click="handleDelEvalCase((scope.row as KbEvalCaseVO))">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">最近一次运行结果</el-divider>
      <div v-if="evalDialog.runResult" class="eval-summary">
        <el-tag type="success">HitRate {{ (((evalDialog.runResult.run?.hitRate ?? evalDialog.runResult.hitRate)) * 100).toFixed(1) }}%</el-tag>
        <el-tag type="primary">MRR {{ (evalDialog.runResult.run?.mrr || 0).toFixed(4) }}</el-tag>
        <el-tag type="primary">Recall {{ (evalDialog.runResult.run?.recallAtK || 0).toFixed(4) }}</el-tag>
        <el-tag type="primary">Precision {{ (evalDialog.runResult.run?.precisionAtK || 0).toFixed(4) }}</el-tag>
        <el-tag type="primary">NDCG {{ (evalDialog.runResult.run?.ndcgAtK || 0).toFixed(4) }}</el-tag>
        <el-tag>有效 {{ evalDialog.runResult.run?.effectiveCases ?? evalDialog.runResult.run?.totalCases ?? 0 }}</el-tag>
        <el-tag v-if="evalDialog.runResult.run?.staleCases" type="warning">过期 {{ evalDialog.runResult.run.staleCases }}</el-tag>
        <el-tag v-if="evalDialog.runResult.run?.degradedCases" type="danger">降级 {{ evalDialog.runResult.run.degradedCases }}</el-tag>
        <el-tag type="info">耗时 {{ evalDialog.runResult.run?.durationMs || 0 }} ms</el-tag>
      </div>
      <el-table v-if="evalDialog.runResult" border :data="evalDialog.runResult.caseResults" max-height="240" size="small">
        <el-table-column label="问题" prop="question" min-width="200" show-overflow-tooltip />
        <el-table-column label="命中" width="70" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.hit ? 'success' : 'danger'" size="small">{{ scope.row.hit ? '命中' : '未命中' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="首中排名" prop="rank" width="80" align="center" />
        <el-table-column label="证据覆盖" width="110" align="center">
          <template #default="scope">{{ (scope.row.evidenceCoverages || []).length ? (scope.row.evidenceCoverages as number[]).map((c: number) => c.toFixed(2)).join(' / ') : '-' }}</template>
        </el-table-column>
        <el-table-column label="相关度" prop="score" width="90" align="center" />
        <el-table-column label="降级" width="70" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.degraded" type="danger" size="small">降级</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="实际命中文件" min-width="140" show-overflow-tooltip>
          <template #default="scope">{{ (scope.row.hitFiles || []).join(' / ') || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="scope">
            <el-button v-if="!scope.row.hit" v-hasPermi="['agent:knowledge:edit']" size="small" text type="warning" @click="handleRegisterIssue(scope.row)">登记质量问题</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="点击「运行评测」批量执行用例并统计五指标" :image-size="60" />

      <el-divider content-position="left">运行历史</el-divider>
      <el-table border :data="evalDialog.runs" max-height="180" size="small">
        <el-table-column label="时间" prop="createTime" width="165" align="center" />
        <el-table-column label="TopK" prop="topK" width="70" align="center" />
        <el-table-column label="有效" prop="effectiveCases" width="70" align="center" />
        <el-table-column label="HitRate" width="80" align="center">
          <template #default="scope">{{ ((scope.row.hitRate ?? (scope.row.totalCases ? scope.row.hitCases / scope.row.totalCases : 0)) * 100).toFixed(1) + '%' }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="80" align="center">
          <template #default="scope">{{ (scope.row.mrr || 0).toFixed(4) }}</template>
        </el-table-column>
        <el-table-column label="NDCG" width="80" align="center">
          <template #default="scope">{{ scope.row.ndcgAtK != null ? (scope.row.ndcgAtK).toFixed(4) : '-' }}</template>
        </el-table-column>
        <el-table-column label="门禁" width="100" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.gateState" :type="scope.row.gateState === 'PASSED' ? 'success' : scope.row.gateState === 'BLOCKED' ? 'danger' : 'info'" size="small">
              {{ scope.row.gateState }}/{{ scope.row.gateReason }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时(ms)" prop="durationMs" width="90" align="center" />
      </el-table>

      <el-divider content-position="left">质量问题({{ evalDialog.issues.length }})</el-divider>
      <el-table v-loading="evalDialog.loading" border :data="evalDialog.issues" max-height="180" size="small">
        <el-table-column label="查询" prop="queryText" min-width="200" show-overflow-tooltip />
        <el-table-column label="原因" width="150" align="center">
          <template #default="scope">
            <el-tag size="small" type="info">{{ scope.row.reason }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" prop="source" width="100" align="center" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.status === 'RESOLVED' ? 'success' : scope.row.status === 'IN_PROGRESS' ? 'primary' : 'warning'" size="small">
              {{ scope.row.status === 'RESOLVED' ? '已解决' : scope.row.status === 'IN_PROGRESS' ? '处理中' : '新建' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联用例" prop="linkedCaseId" width="90" align="center" />
        <el-table-column label="操作" width="180" align="center">
          <template #default="scope">
            <el-button v-if="scope.row.status !== 'RESOLVED'" v-hasPermi="['agent:knowledge:edit']" size="small" text type="primary" @click="handleIssueTransition((scope.row as KbQualityIssueVO), scope.row.status === 'NEW' ? 'IN_PROGRESS' : 'RESOLVED')">
              {{ scope.row.status === 'NEW' ? '开始处理' : '标记解决' }}
            </el-button>
            <el-button v-else v-hasPermi="['agent:knowledge:edit']" size="small" text type="warning" @click="handleIssueTransition((scope.row as KbQualityIssueVO), 'IN_PROGRESS')">重新打开</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 索引重建进度 -->
    <el-dialog v-model="rebuildProgress.visible" :title="'索引重建进度 - ' + rebuildProgress.kbName" width="460px" @closed="stopRebuildPolling">
      <div v-if="rebuildProgress.data">
        <el-progress :percentage="rebuildProgress.data.percent || 0" :stroke-width="14" :status="rebuildProgress.data.percent >= 100 ? 'success' : undefined" />
        <div class="progress-tip">
          {{ rebuildProgress.data.success || 0 }} 完成 ·
          <span :class="rebuildProgress.data.failed ? 'progress-failed' : ''">{{ rebuildProgress.data.failed || 0 }} 失败</span>
          · {{ rebuildProgress.data.pending || 0 }} 待处理(共 {{ rebuildProgress.data.total || 0 }} 篇文档)
        </div>
        <div v-if="(rebuildProgress.data.pending || 0) > 0" class="form-tip">后台解析中,进度每 2.5 秒自动刷新;期间检索结果可能不全</div>
      </div>
      <div v-else v-loading="true" element-loading-text="加载进度中" style="height: 60px" />
    </el-dialog>

    <!-- 零命中查询日志(知识缺口) -->
    <el-dialog v-model="zeroHitDialog.visible" title="零命中查询 · 知识缺口" width="920px" destroy-on-close>
      <div class="doc-toolbar">
        <el-input v-model="zeroHitDialog.query.keyword" placeholder="按问题关键字过滤" clearable style="width: 240px" @keyup.enter="handleZeroHitQuery" />
        <el-button type="primary" icon="Search" @click="handleZeroHitQuery">搜索</el-button>
        <el-button icon="Refresh" @click="loadZeroHit">刷新</el-button>
      </div>
      <el-table v-loading="zeroHitDialog.loading" border :data="zeroHitDialog.list" max-height="440">
        <el-table-column label="时间" prop="createTime" width="165" align="center" />
        <el-table-column label="Agent" prop="agentName" width="130" show-overflow-tooltip />
        <el-table-column label="用户问题" prop="originalQuery" min-width="210" show-overflow-tooltip />
        <el-table-column label="检索查询" prop="rewrittenQuery" min-width="210" show-overflow-tooltip />
        <el-table-column label="知识库ID" prop="knowledgeIds" width="100" align="center" />
      </el-table>
      <el-empty v-if="!zeroHitDialog.loading && zeroHitDialog.list.length === 0" description="暂无零命中记录" />
      <pagination v-model:page="zeroHitDialog.query.pageNum" v-model:limit="zeroHitDialog.query.pageSize" :total="zeroHitDialog.total" @pagination="loadZeroHit" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentKnowledge">
import {
  addKnowledge, changeKnowledgeStatus, delKnowledge, listKnowledge, updateKnowledge,
  listKnowledgeDocument, uploadKnowledgeDocument, retryKnowledgeDocument, delKnowledgeDocument,
  listKnowledgeChunks, downloadKnowledgeDocument, searchKnowledge,
  updateKnowledgeChunk, changeKnowledgeChunkEnabled, listKnowledgeZeroHit, rebuildKnowledgeIndex,
  rebuildStaleKnowledge, getRebuildProgress, listKbEvalCases, addKbEvalCase, delKbEvalCase, runKbEval, listKbEvalRuns,
  pageKbQualityIssues, registerKbQualityIssue, changeKbQualityIssueStatus
} from '@/api/agent/knowledge';
import type {
  KnowledgeForm, KnowledgeQuery, KnowledgeVO, KnowledgeDocumentVO, KnowledgeChunkVO, KnowledgeSearchHitVO, KnowledgeZeroHitVO,
  KbEvalCaseVO, KbEvalRunVO, KbQualityIssueVO, KbRebuildProgressVO
} from '@/api/agent/knowledge/types';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const knowledgeList = ref<KnowledgeVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:knowledge:edit']);
const hasChunkPerm = checkPermi(['agent:knowledgeDoc:chunk']);
const workspaceOptions = ref<WorkspaceVO[]>([]);
const workspaceNameMap = computed(() => {
  const m: Record<string, string> = {};
  workspaceOptions.value.forEach((w) => (m[String(w.id)] = w.name));
  return m;
});
const workspaceName = (id: number | string) => workspaceNameMap.value[String(id)] || '未知空间';

const queryParam = ref<KnowledgeQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '', workspaceId: undefined });

const loadWorkspaces = async () => {
  try {
    const res = await myWorkspaces();
    workspaceOptions.value = res.data || [];
  } catch {
    workspaceOptions.value = [];
  }
};

const dialog = reactive({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: KnowledgeForm = { id: undefined, workspaceId: undefined, name: '', description: '', embeddingModel: 'local-hash', rerankModel: '', scoreThreshold: null, rerankThreshold: null, fallbackAnswerEnabled: 1, parentChunkEnabled: '0', parentChunkSize: 2000, chunkSize: 500, chunkOverlap: 50, chunkUnit: 'char', splitStrategy: 'structure', cleanHeaderFooter: true, cleanBlankNormalize: true, cleanNfkc: false, cleanPiiMask: false, remark: '' };
const form = ref<KnowledgeForm>({ ...initForm });

const rules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }]
};

const docDialog = reactive({
  visible: false,
  title: '',
  knowledgeId: 0 as number | string,
  loading: false,
  uploading: false,
  list: [] as KnowledgeDocumentVO[],
  total: 0,
  query: { pageNum: 1, pageSize: 10, knowledgeId: 0 as number | string }
});

const chunkDialog = reactive({ visible: false, title: '', loading: false, documentId: 0 as number | string, list: [] as KnowledgeChunkVO[] });
const chunkEditDialog = reactive({ visible: false, saving: false, id: 0 as number | string, content: '' });

const searchDialog = reactive({
  visible: false,
  title: '',
  knowledgeId: 0 as number | string,
  query: '',
  topK: 5,
  searchMode: 'hybrid',
  rerank: false,
  loading: false,
  results: [] as KnowledgeSearchHitVO[],
  searched: false
});

const zeroHitDialog = reactive({
  visible: false,
  loading: false,
  list: [] as KnowledgeZeroHitVO[],
  total: 0,
  query: { pageNum: 1, pageSize: 10, keyword: '' }
});

const getList = async () => {
  loading.value = true;
  try {
    const res = await listKnowledge(queryParam.value);
    knowledgeList.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParam.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParam.value = { pageNum: 1, pageSize: 10, keyword: '', status: '', workspaceId: undefined };
  handleQuery();
};

const handleAdd = () => {
  isEdit.value = false;
  form.value = { ...initForm };
  dialog.title = '新建知识库';
  dialog.visible = true;
};

const handleEdit = (row: KnowledgeVO) => {
  isEdit.value = true;
  let clean = { headerFooter: true, blankNormalize: true, nfkc: false, piiMask: false };
  try {
    if (row.cleanRules) {
      clean = { ...clean, ...JSON.parse(row.cleanRules) };
    }
  } catch {
    // 坏 JSON 按默认开关展示(后端摄取时同样回默认)
  }
  form.value = {
    ...row,
    scoreThreshold: row.scoreThreshold === null || row.scoreThreshold === undefined ? null : Number(row.scoreThreshold),
    rerankThreshold: row.rerankThreshold === null || row.rerankThreshold === undefined ? null : Number(row.rerankThreshold),
    fallbackAnswerEnabled: row.fallbackAnswerEnabled === null || row.fallbackAnswerEnabled === undefined ? 1 : Number(row.fallbackAnswerEnabled),
    parentChunkEnabled: row.parentChunkEnabled || '0',
    parentChunkSize: row.parentChunkSize || 2000,
    chunkUnit: row.chunkUnit || 'char',
    splitStrategy: row.splitStrategy || 'structure',
    cleanHeaderFooter: clean.headerFooter !== false,
    cleanBlankNormalize: clean.blankNormalize !== false,
    cleanNfkc: clean.nfkc === true,
    cleanPiiMask: clean.piiMask === true
  };
  dialog.title = '编辑知识库';
  dialog.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return;
    dialog.saving = true;
    try {
      const payload = {
        ...form.value,
        cleanRules: JSON.stringify({
          headerFooter: form.value.cleanHeaderFooter !== false,
          blankNormalize: form.value.cleanBlankNormalize !== false,
          nfkc: form.value.cleanNfkc === true,
          piiMask: form.value.cleanPiiMask === true
        })
      };
      if (isEdit.value) {
        await updateKnowledge(payload);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addKnowledge(payload);
        proxy?.$modal.msgSuccess('新建成功');
      }
      dialog.visible = false;
      getList();
    } finally {
      dialog.saving = false;
    }
  });
};

const handleStatusChange = async (row: KnowledgeVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  const text = next === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm(`确认要${text}知识库「${row.name}」吗?`);
    await changeKnowledgeStatus(row.id, next);
    proxy?.$modal.msgSuccess(`${text}成功`);
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: KnowledgeVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除知识库「${row.name}」吗?`);
    await delKnowledge(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleRebuild = async (row: KnowledgeVO) => {
  try {
    const stale = row.staleCount ?? 0;
    if (stale > 0) {
      // 差异重建(R2-5):只重建配置过期的文档,其余文档与解析产物不受影响
      await proxy?.$modal.confirm(
        `知识库「${row.name}」有 ${stale} 篇文档的配置已过期。\n只重建这些文档(其余文档与解析产物不受影响,期间旧索引照常可用)?`
      );
      const res = await rebuildStaleKnowledge(row.id);
      proxy?.$modal.msgSuccess(`已触发差异重建,共 ${res.data ?? stale} 篇文档后台处理`);
      getList();
      startRebuildPolling(row);
      return;
    }
    await proxy?.$modal.confirm(
      `确认重建知识库「${row.name}」的索引吗?\n将按当前嵌入模型/分块参数重新解析全部文档(按代次重建,期间旧索引照常可用)。`
    );
    await rebuildKnowledgeIndex(row.id);
    proxy?.$modal.msgSuccess('已触发重建索引,文档将在后台重新处理');
    getList();
    startRebuildPolling(row);
  } catch (e) {
    // 用户取消或失败
  }
};

// ==================== 索引重建进度 ====================
const rebuildProgress = reactive({
  visible: false,
  kbName: '',
  data: null as KbRebuildProgressVO | null,
  timer: null as ReturnType<typeof setTimeout> | null
});

const stopRebuildPolling = () => {
  if (rebuildProgress.timer) {
    clearTimeout(rebuildProgress.timer);
    rebuildProgress.timer = null;
  }
};

const pollRebuildProgress = async (id: number | string) => {
  try {
    const res = await getRebuildProgress(id);
    rebuildProgress.data = res.data || null;
    if (!rebuildProgress.data || (rebuildProgress.data.pending || 0) === 0) {
      stopRebuildPolling();
      getList();
      return;
    }
  } catch {
    stopRebuildPolling();
    return;
  }
  rebuildProgress.timer = setTimeout(() => pollRebuildProgress(id), 2500);
};

const startRebuildPolling = (row: KnowledgeVO) => {
  stopRebuildPolling();
  rebuildProgress.kbName = row.name;
  rebuildProgress.data = null;
  rebuildProgress.visible = true;
  pollRebuildProgress(row.id);
};

onUnmounted(() => stopRebuildPolling());

// ==================== 检索评测 ====================
const evalDialog = reactive({
  visible: false,
  knowledgeId: 0 as number | string,
  loading: false,
  running: false,
  saving: false,
  topK: 5,
  cases: [] as KbEvalCaseVO[],
  newCase: { question: '', expectedDocName: '', expectedKeywords: '', evidences: '' } as KbEvalCaseVO,
  runResult: null as Record<string, any> | null,
  runs: [] as KbEvalRunVO[],
  issues: [] as KbQualityIssueVO[]
});

const handleEval = () => {
  if (!knowledgeList.value.length) {
    proxy?.$modal.msgWarning('请先创建知识库并上传文档');
    return;
  }
  evalDialog.knowledgeId = knowledgeList.value[0].id;
  evalDialog.topK = 5;
  evalDialog.runResult = null;
  evalDialog.visible = true;
  loadEvalAll();
};

const loadEvalAll = async () => {
  if (!evalDialog.knowledgeId) return;
  evalDialog.loading = true;
  try {
    const [caseRes, runRes, issueRes] = await Promise.all([
      listKbEvalCases(evalDialog.knowledgeId),
      listKbEvalRuns(evalDialog.knowledgeId, 10),
      pageKbQualityIssues(evalDialog.knowledgeId, undefined, 1, 50)
    ]);
    evalDialog.cases = caseRes.data || [];
    evalDialog.runs = runRes.data || [];
    evalDialog.issues = issueRes.data?.rows || [];
  } finally {
    evalDialog.loading = false;
  }
};

const handleAddEvalCase = async () => {
  if (!evalDialog.newCase.question.trim()) {
    proxy?.$modal.msgWarning('请输入评测问题');
    return;
  }
  const evidences = evalDialog.newCase.evidences?.trim();
  if (evidences) {
    try {
      const parsed = JSON.parse(evidences);
      if (!Array.isArray(parsed) || !parsed.length) {
        proxy?.$modal.msgWarning('证据标注必须是以下格式的非空数组');
        return;
      }
    } catch {
      proxy?.$modal.msgWarning('证据标注 JSON 不合法');
      return;
    }
  } else if (!evalDialog.newCase.expectedDocName && !evalDialog.newCase.expectedKeywords) {
    proxy?.$modal.msgWarning('证据标注与期望文档名/关键词至少填一项,否则无法判定命中');
    return;
  }
  evalDialog.saving = true;
  try {
    await addKbEvalCase({
      knowledgeId: evalDialog.knowledgeId,
      question: evalDialog.newCase.question.trim(),
      expectedDocName: evalDialog.newCase.expectedDocName?.trim(),
      expectedKeywords: evalDialog.newCase.expectedKeywords?.trim(),
      evidences: evidences || undefined
    });
    evalDialog.newCase = { knowledgeId: evalDialog.knowledgeId, question: '', expectedDocName: '', expectedKeywords: '', evidences: '' };
    proxy?.$modal.msgSuccess('用例已添加');
    loadEvalAll();
  } finally {
    evalDialog.saving = false;
  }
};

const handleDelEvalCase = async (row: KbEvalCaseVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除用例「${row.question}」吗?`);
    await delKbEvalCase(row.id);
    loadEvalAll();
  } catch (e) {
    // 用户取消
  }
};

const handleRunEval = async () => {
  if (!evalDialog.cases.length) {
    proxy?.$modal.msgWarning('请先添加评测用例');
    return;
  }
  evalDialog.running = true;
  try {
    const res = await runKbEval(evalDialog.knowledgeId, evalDialog.topK);
    evalDialog.runResult = res.data || null;
    proxy?.$modal.msgSuccess('评测完成');
    listKbEvalRuns(evalDialog.knowledgeId, 10).then((r) => (evalDialog.runs = r.data || []));
  } finally {
    evalDialog.running = false;
  }
};

/** 未命中的用例一键登记质量问题(source=CASE_FAIL,后端按 queryHash 去重) */
const handleRegisterIssue = async (row: Record<string, any>) => {
  try {
    await registerKbQualityIssue({
      knowledgeId: evalDialog.knowledgeId,
      queryText: String(row.question),
      reason: 'RETRIEVAL_MISS',
      source: 'CASE_FAIL',
      note: '评测未命中自动登记'
    });
    proxy?.$modal.msgSuccess('已登记质量问题');
    loadEvalAll();
  } catch (e) {
    // 后端报错已统一提示
  }
};

/** 质量问题状态流转(乐观锁 revision) */
const handleIssueTransition = async (row: KbQualityIssueVO, target: string) => {
  try {
    await changeKbQualityIssueStatus(row.id!, target, row.note, row.revision);
    proxy?.$modal.msgSuccess(target === 'RESOLVED' ? '已标记解决' : '已更新状态');
    loadEvalAll();
  } catch (e) {
    // revision 不一致等由后端统一提示
    loadEvalAll();
  }
};

// ==================== 文档管理 ====================
const handleDocs = (row: KnowledgeVO) => {
  docDialog.knowledgeId = row.id;
  docDialog.title = `文档管理 - ${row.name}`;
  docDialog.query.knowledgeId = row.id;
  docDialog.query.pageNum = 1;
  docDialog.visible = true;
  loadDocs();
};

const loadDocs = async () => {
  docDialog.loading = true;
  try {
    const res = await listKnowledgeDocument(docDialog.query);
    docDialog.list = res.data?.rows || [];
    docDialog.total = res.data?.total || 0;
  } finally {
    docDialog.loading = false;
  }
};

const handleUpload = async (options: any) => {
  docDialog.uploading = true;
  try {
    await uploadKnowledgeDocument(docDialog.knowledgeId, options.file as File);
    proxy?.$modal.msgSuccess('上传成功,正在异步解析向量化');
    loadDocs();
  } finally {
    docDialog.uploading = false;
  }
};

const handleRetry = async (row: KnowledgeDocumentVO) => {
  try {
    await retryKnowledgeDocument(row.id);
    proxy?.$modal.msgSuccess('已触发重试');
    loadDocs();
  } catch (e) {
    // 失败由全局拦截提示
  }
};

const handleDocDelete = async (row: KnowledgeDocumentVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除文档「${row.fileName}」吗?`);
    await delKnowledgeDocument(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    loadDocs();
  } catch (e) {
    // 用户取消
  }
};

const handleChunks = async (row: KnowledgeDocumentVO) => {
  chunkDialog.title = `分块管理 - ${row.fileName}`;
  chunkDialog.documentId = row.id;
  chunkDialog.loading = true;
  chunkDialog.visible = true;
  try {
    const res = await listKnowledgeChunks(row.id);
    chunkDialog.list = res.data || [];
  } finally {
    chunkDialog.loading = false;
  }
};

const reloadChunks = async () => {
  const res = await listKnowledgeChunks(chunkDialog.documentId);
  chunkDialog.list = res.data || [];
};

const handleChunkEdit = (row: KnowledgeChunkVO) => {
  chunkEditDialog.id = row.id;
  chunkEditDialog.content = row.content;
  chunkEditDialog.visible = true;
};

const submitChunkEdit = async () => {
  if (!chunkEditDialog.content.trim()) {
    proxy?.$modal.msgWarning('分块内容不能为空');
    return;
  }
  chunkEditDialog.saving = true;
  try {
    await updateKnowledgeChunk({ id: chunkEditDialog.id, content: chunkEditDialog.content });
    proxy?.$modal.msgSuccess('保存成功,已重新向量化');
    chunkEditDialog.visible = false;
    reloadChunks();
  } finally {
    chunkEditDialog.saving = false;
  }
};

const handleChunkEnabled = async (row: KnowledgeChunkVO, val: string | number | boolean) => {
  const next = !!val;
  try {
    await changeKnowledgeChunkEnabled(row.id, next);
    proxy?.$modal.msgSuccess(next ? '已启用' : '已禁用,不再参与检索');
    reloadChunks();
  } catch (e) {
    // 失败由全局拦截提示
  }
};

const handleDownload = async (row: KnowledgeDocumentVO) => {
  const res = await downloadKnowledgeDocument(row.id);
  const blob = new Blob([res.data as BlobPart]);
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = row.fileName;
  a.click();
  window.URL.revokeObjectURL(url);
};

// ==================== 检索测试 ====================
const handleSearch = (row: KnowledgeVO) => {
  searchDialog.knowledgeId = row.id;
  searchDialog.title = `检索测试 - ${row.name}`;
  searchDialog.query = '';
  searchDialog.topK = 5;
  searchDialog.rerank = false;
  searchDialog.results = [];
  searchDialog.searched = false;
  searchDialog.visible = true;
};

const runSearch = async () => {
  if (!searchDialog.query.trim()) {
    proxy?.$modal.msgWarning('请输入查询内容');
    return;
  }
  searchDialog.loading = true;
  try {
    const res = await searchKnowledge({
      knowledgeId: searchDialog.knowledgeId,
      query: searchDialog.query,
      topK: searchDialog.topK,
      searchMode: searchDialog.searchMode,
      rerank: searchDialog.rerank
    });
    searchDialog.results = res.data || [];
    searchDialog.searched = true;
  } finally {
    searchDialog.loading = false;
  }
};

const resetSearch = () => {
  searchDialog.query = '';
  searchDialog.topK = 5;
  searchDialog.searchMode = 'hybrid';
  searchDialog.rerank = false;
  searchDialog.results = [];
  searchDialog.searched = false;
};

// ==================== 零命中查询 ====================
const handleZeroHit = () => {
  zeroHitDialog.query.pageNum = 1;
  zeroHitDialog.query.keyword = '';
  zeroHitDialog.visible = true;
  loadZeroHit();
};

const loadZeroHit = async () => {
  zeroHitDialog.loading = true;
  try {
    const res = await listKnowledgeZeroHit(zeroHitDialog.query);
    zeroHitDialog.list = res.data?.rows || [];
    zeroHitDialog.total = res.data?.total || 0;
  } finally {
    zeroHitDialog.loading = false;
  }
};

const handleZeroHitQuery = () => {
  zeroHitDialog.query.pageNum = 1;
  loadZeroHit();
};

const formatSize = (bytes: number) => {
  if (!bytes) return '0 B';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(2) + ' MB';
};

onMounted(() => {
  loadWorkspaces();
  getList();
});
</script>

<style scoped>
.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.doc-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.hit-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
  background: #fafbfc;
}
.hit-head {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}
.hit-rank {
  font-weight: 700;
  color: #3b5bdb;
}
.hit-score {
  color: #2f9e44;
  font-weight: 600;
}
.hit-source {
  color: #909399;
  font-size: 12px;
}
.rerank-tip {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
.hit-rerank {
  color: #b26a00;
}
.hit-keyword {
  color: #7c3aed;
}
.hit-rrf {
  color: #0369a1;
}
.hit-content {
  font-size: 13px;
  color: #303133;
  white-space: pre-wrap;
  line-height: 1.6;
}
.eval-summary {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.progress-tip {
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}
.progress-failed {
  color: #f56c6c;
  font-weight: 600;
}
.clean-switches {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}
</style>
