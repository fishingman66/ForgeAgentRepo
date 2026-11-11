<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="Agent名称">
          <el-input v-model="queryParam.keyword" placeholder="请输入名称关键字" clearable style="width: 180px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="工作空间">
          <el-select v-model="queryParam.workspaceId" placeholder="全部空间" clearable filterable style="width: 180px">
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParam.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="草稿" value="0" />
            <el-option label="已发布" value="1" />
            <el-option label="已停用" value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="sortField" placeholder="默认排序" clearable style="width: 150px" @change="handleSortChange">
            <el-option label="创建时间 ↓" value="createTime_desc" />
            <el-option label="创建时间 ↑" value="createTime_asc" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          <el-button icon="Document" @click="promptVisible = true">Prompt 模板中心</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel" style="margin-top: 10px">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>Agent 管理</h3>
            <span class="heading-tip">支持表单配置与拖拽工作流两种编排方式</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:agent:add']" type="primary" plain icon="Plus" @click="handleAdd">新建 Agent</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="agentList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="名称" prop="name" min-width="130" show-overflow-tooltip />
        <el-table-column label="所属空间" prop="workspaceName" min-width="120" show-overflow-tooltip />
        <el-table-column label="描述" prop="description" min-width="140" show-overflow-tooltip />
        <el-table-column label="绑定模型" min-width="110" show-overflow-tooltip>
          <template #default="scope">
            <span v-if="scope.row.modelName">{{ scope.row.modelName }}</span>
            <span v-else style="color: var(--el-text-color-secondary)">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="470" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:agent:list']" size="small" text type="info" icon="Files" @click="openVersions(scope.row as AgentVO)">版本</el-button>
            <el-button v-hasPermi="['agent:agent:list']" size="small" text type="info" icon="DataAnalysis" @click="openEval(scope.row as AgentVO)">评测</el-button>
            <el-button v-if="scope.row.status !== '1'" v-hasPermi="['agent:agent:edit']" size="small" text type="success" icon="Promotion" @click="handlePublish(scope.row as AgentVO)">发布</el-button>
            <el-button v-if="scope.row.status === '1'" v-hasPermi="['agent:agent:edit']" size="small" text type="warning" icon="SwitchButton" @click="handleOffline(scope.row as AgentVO)">停用</el-button>
            <el-button v-hasPermi="['agent:agent:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as AgentVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:agent:add']" size="small" text type="success" icon="CopyDocument" @click="handleClone(scope.row as AgentVO)">克隆</el-button>
            <el-button v-hasPermi="['agent:agent:edit']" size="small" text type="warning" icon="Guide" @click="handleWorkflow(scope.row as AgentVO)">工作流</el-button>
            <el-button v-hasPermi="['agent:agent:edit']" size="small" text type="primary" icon="Link" @click="openEndpoints(scope.row as AgentVO)">API</el-button>
            <el-button v-hasPermi="['agent:agent:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as AgentVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新建/编辑 Agent -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="680px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="工作空间" prop="workspaceId">
          <el-select v-model="form.workspaceId" placeholder="请选择工作空间" :disabled="isEdit" filterable style="width: 100%">
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,空间内唯一" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>

        <el-divider content-position="left">模型与参数</el-divider>
        <el-form-item label="绑定模型" prop="modelId">
          <el-select v-model="form.modelId" placeholder="请选择模型(发布前必须绑定)" clearable filterable style="width: 100%">
            <el-option v-for="m in modelOptions" :key="m.id" :label="`${m.name}(${m.modelName})`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="温度" prop="temperature">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大tokens" prop="maxTokens">
              <el-input-number v-model="form.maxTokens" :min="1" :max="100000" :step="256" style="width: 100%" controls-position="right" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="Token预算" prop="tokenBudget">
          <el-input-number v-model="form.tokenBudget" :min="1" :max="999999999" :step="10000" style="width: 100%" controls-position="right" placeholder="留空不限制" />
          <div class="form-tip">Agent 累计 Token 总量上限(跨会话),达到上限后自动终止执行;留空表示不限制</div>
        </el-form-item>

        <el-divider content-position="left">高级能力</el-divider>
        <el-form-item label="结构化输出" prop="outputSchema">
          <el-input
            v-model="form.outputSchema"
            type="textarea"
            :rows="4"
            maxlength="10000"
            placeholder='JSON Schema 对象,如 {"type":"object","properties":{"answer":{"type":"string"},"score":{"type":"number"}},"required":["answer"]}'
          />
          <div class="form-tip">配置后回复为符合该 Schema 的 JSON;与团队协作互斥</div>
        </el-form-item>
        <el-form-item label="团队协作" prop="teamMode">
          <el-radio-group v-model="form.teamMode">
            <el-radio value="none">不启用</el-radio>
            <el-radio value="sequential">顺序接力</el-radio>
            <el-radio value="parallel">并行汇总</el-radio>
            <el-radio value="reviewer">末棒复核</el-radio>
            <el-radio value="supervisor">主控规划</el-radio>
          </el-radio-group>
          <div class="form-tip">顺序接力逐棒传递;并行汇总由有界线程池同时执行后确定性 JOIN;末棒复核让最后一个成员检查结论;主控规划先拆子任务。成员绑定了自己的工具时可带工具(并行模式为规避 Toolkit 并发共享不注入)</div>
        </el-form-item>
        <el-form-item v-if="form.teamMode !== 'none'" label="协作成员" prop="teamIds">
          <el-select v-model="form.teamIds" placeholder="选择本空间其他 Agent(可多选)" multiple clearable filterable style="width: 100%">
            <el-option v-for="a in memberOptions" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="长期记忆" prop="memoryEnabled">
          <el-switch v-model="form.memoryEnabled" />
          <div class="form-tip">开启后每轮对话结束自动提取值得记住的信息,并在后续对话注入;可在「长期记忆」页面维护</div>
        </el-form-item>
        <el-form-item label="工作流触发" prop="workflowTriggerMode">
          <el-radio-group v-model="form.workflowTriggerMode">
            <el-radio value="0">关闭</el-radio>
            <el-radio value="1">每轮自动执行</el-radio>
            <el-radio value="2">按意图路由</el-radio>
          </el-radio-group>
          <div class="form-tip">需先通过「工作流」画布完成编排;每轮自动执行:所有对话直接按工作流执行;按意图路由:问题命中工具或知识意图时走工作流,闲聊仍走模型对话。工作流执行失败时,失败原因将作为回复返回,不回退到模型对话</div>
        </el-form-item>

        <el-divider content-position="left">提示词</el-divider>
        <el-form-item label="系统提示词" prop="systemPrompt">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4" maxlength="5000" show-word-limit placeholder="定义 Agent 的角色与行为" />
        </el-form-item>
        <el-form-item label="欢迎语" prop="welcomeMsg">
          <el-input v-model="form.welcomeMsg" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="会话开场白(可选)" />
        </el-form-item>
        <el-form-item label="推荐问题" prop="suggestedQuestions">
          <el-input
            v-model="suggestedText" type="textarea" :rows="3" maxlength="1000" show-word-limit
            placeholder="每行一个推荐问题,会话空态展示为可点击快捷提问(可选,最多 10 条)"
          />
        </el-form-item>

        <el-divider content-position="left">能力绑定</el-divider>
        <el-form-item label="绑定工具" prop="toolIds">
          <el-select v-model="form.toolIds" placeholder="可多选" multiple clearable filterable style="width: 100%">
            <el-option v-for="t in toolOptions" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定MCP" prop="mcpIds">
          <el-select v-model="form.mcpIds" placeholder="可多选,仅列出启用中的 MCP 服务器" multiple clearable filterable style="width: 100%">
            <el-option v-for="m in mcpOptions" :key="m.id" :label="m.name" :value="m.id">
              <span>{{ m.name }}</span>
              <span style="float: right; color: var(--el-text-color-secondary); font-size: 12px">{{ m.toolCount ?? 0 }} 个工具</span>
            </el-option>
          </el-select>
          <div class="form-tip">MCP 服务器的审批模式(auto/ask/deny)决定其工具调用是否需要人工审批</div>
        </el-form-item>
        <el-form-item label="绑定知识库" prop="knowledgeIds">
          <el-select v-model="form.knowledgeIds" placeholder="可多选" multiple clearable filterable style="width: 100%">
            <el-option v-for="k in knowledgeOptions" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">Prompt 模板绑定(P1)</el-divider>
        <el-form-item label="绑定模板">
          <el-select v-model="form.promptTemplateId" placeholder="不绑定(使用上方系统提示词)" clearable filterable style="width: 100%">
            <el-option v-for="t in promptTemplates" :key="t.id" :label="`${t.name} (${t.category})`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定模式">
          <el-radio-group v-model="form.promptBindingMode" :disabled="!form.promptTemplateId">
            <el-radio value="MANUAL">手动</el-radio>
            <el-radio value="LOCKED">锁定版本</el-radio>
            <el-radio value="FOLLOW_STABLE">跟随稳定版</el-radio>
          </el-radio-group>
          <div class="form-tip">锁定版本需填版本ID;跟随稳定版会随模板晋级自动生效,便于灰度与回滚</div>
        </el-form-item>
        <el-form-item v-if="form.promptBindingMode === 'LOCKED'" label="版本ID">
          <el-input v-model="form.promptVersionId" placeholder="在 Prompt 模板中心复制版本ID" />
        </el-form-item>
        <el-form-item v-if="form.promptTemplateId && form.promptBindingMode !== 'MANUAL'" label="变量值">
          <el-input v-model="form.promptVariables" type="textarea" :rows="2" placeholder='JSON 对象,如 {"tone":"专业"}' />
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon title="点击「工作流」按钮进入拖拽画布编排;当前表单用于基础配置" />
      </el-form>
      <template #footer>
        <el-button v-if="isEdit" type="warning" plain icon="ChatDotRound" @click="debugVisible = true">调试</el-button>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- UX-01 调试抽屉:基于当前表单未保存配置流式调试 -->
    <DebugDrawer v-model="debugVisible" :draft="debugDraft" />

    <!-- P1:版本/门禁、效果评测、Prompt 模板中心 -->
    <VersionDrawer v-model="versionVisible" :agent-id="versionAgent.id" :agent-name="versionAgent.name" @rolled-back="getList" />
    <EvalDrawer v-model="evalVisible" :agent-id="evalAgentId" :workspace-id="queryParam.workspaceId" />
    <PromptCenterDrawer v-model="promptVisible" :workspace-id="queryParam.workspaceId" @changed="loadPromptTemplates" />
    <EndpointDrawer v-model="endpointVisible" :agent-id="endpointAgentId" />
  </div>
</template>

<script setup lang="ts" name="AgentAgent">
import { useRouter } from 'vue-router';
import { addAgent, changeAgentStatus, cloneAgent, delAgent, getAgent, listAgent, updateAgent } from '@/api/agent/agent';
import type { AgentForm, AgentQuery, AgentVO } from '@/api/agent/agent/types';
import { myWorkspaces } from '@/api/agent/workspace';
import { listModel } from '@/api/agent/model';
import { listTool } from '@/api/agent/tool';
import { listMcpServer } from '@/api/agent/mcp';
import { listKnowledge } from '@/api/agent/knowledge';
import type { WorkspaceVO } from '@/api/agent/workspace/types';
import type { ModelVO } from '@/api/agent/model/types';
import type { ToolVO } from '@/api/agent/tool/types';
import type { McpServerVO } from '@/api/agent/mcp/types';
import type { KnowledgeVO } from '@/api/agent/knowledge/types';
import type { AgentDraftForm } from '@/api/agent/session/types';
import DebugDrawer from './components/DebugDrawer.vue';
import VersionDrawer from './components/VersionDrawer.vue';
import EvalDrawer from './components/EvalDrawer.vue';
import PromptCenterDrawer from './components/PromptCenterDrawer.vue';
import EndpointDrawer from './components/EndpointDrawer.vue';
import { listPromptTemplates, previewGate, type PromptTemplate } from '@/api/agent/governance';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const router = useRouter();

const loading = ref(false);
const agentList = ref<AgentVO[]>([]);
const total = ref(0);
const workspaceOptions = ref<WorkspaceVO[]>([]);
const modelOptions = ref<ModelVO[]>([]);
const toolOptions = ref<ToolVO[]>([]);
const mcpOptions = ref<McpServerVO[]>([]);
const knowledgeOptions = ref<KnowledgeVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);

const queryParam = ref<AgentQuery>({ pageNum: 1, pageSize: 10, keyword: '' });
const sortField = ref('');

/** 排序切换:映射到 PageQuery 的 orderByColumn/isAsc */
const handleSortChange = (v: string) => {
  if (v) {
    const [col, dir] = v.split('_');
    queryParam.value.orderByColumn = col;
    queryParam.value.isAsc = dir;
  } else {
    queryParam.value.orderByColumn = undefined;
    queryParam.value.isAsc = undefined;
  }
  handleQuery();
};

const statusMap: Record<string, { label: string; type: 'info' | 'success' | 'warning' }> = {
  '0': { label: '草稿', type: 'info' },
  '1': { label: '已发布', type: 'success' },
  '2': { label: '已停用', type: 'warning' }
};
const statusLabel = (s: string) => statusMap[s]?.label || s;
const statusType = (s: string): 'info' | 'success' | 'warning' => statusMap[s]?.type || 'info';

const dialog = reactive({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: AgentForm = {
  id: undefined, workspaceId: undefined, name: '', description: '', systemPrompt: '', welcomeMsg: '',
  modelId: null, temperature: 0.7, maxTokens: 4096, tokenBudget: undefined,
  outputSchema: '', memoryEnabled: false, teamMode: 'none', teamIds: [], workflowTriggerMode: '0',
  toolIds: [], mcpIds: [], knowledgeIds: [],
  promptTemplateId: null, promptBindingMode: 'MANUAL', promptVersionId: null, promptVariables: ''
};
const form = ref<AgentForm>({ ...initForm });

/* ---------- P1:版本/门禁、评测、Prompt 中心 ---------- */
const versionVisible = ref(false);
const versionAgent = ref<{ id: number | string; name: string }>({ id: '', name: '' });
const evalVisible = ref(false);
const evalAgentId = ref<number | string>('');
const promptVisible = ref(false);
const promptTemplates = ref<PromptTemplate[]>([]);

const loadPromptTemplates = async () => {
  const res = await listPromptTemplates(queryParam.value.workspaceId);
  promptTemplates.value = res.data ?? [];
};

const openVersions = (row: AgentVO) => {
  versionAgent.value = { id: row.id, name: row.name };
  versionVisible.value = true;
};

const openEval = (row: AgentVO) => {
  evalAgentId.value = row.id;
  evalVisible.value = true;
};

/* ---------- G2:工作流 API 端点 ---------- */
const endpointVisible = ref(false);
const endpointAgentId = ref<number | string>('');
const openEndpoints = (row: AgentVO) => {
  endpointAgentId.value = row.id;
  endpointVisible.value = true;
};
/** 推荐问题编辑文本(每行一项,提交时转 JSON 数组) */
const suggestedText = ref('');

/* ---------- UX-01 调试抽屉 ---------- */
const debugVisible = ref(false);
/** 草稿:取当前表单实时值(未保存也生效),数组转逗号串与后端 AgentDraftBo 对应 */
const debugDraft = computed<AgentDraftForm | null>(() => {
  if (!form.value.id) return null;
  return {
    agentId: form.value.id,
    systemPrompt: form.value.systemPrompt || undefined,
    modelId: form.value.modelId ?? undefined,
    toolIdsStr: (form.value.toolIds || []).join(',') || undefined,
    knowledgeIdsStr: (form.value.knowledgeIds || []).join(',') || undefined,
    mcpIdsStr: (form.value.mcpIds || []).join(',') || undefined
  };
});

const validateOutputSchema = (_rule: any, value: string, callback: any) => {
  if (!value || !value.trim()) return callback();
  try {
    const parsed = JSON.parse(value);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return callback(new Error('Schema 须为 JSON 对象'));
    }
    callback();
  } catch (e) {
    callback(new Error('不是合法的 JSON'));
  }
};

const rules = {
  workspaceId: [{ required: true, message: '请选择工作空间', trigger: 'change' }],
  name: [{ required: true, message: '请输入 Agent 名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }],
  outputSchema: [{ validator: validateOutputSchema, trigger: 'blur' }],
  teamIds: [{ required: true, type: 'array' as const, message: '请至少选择 1 个协作成员', trigger: 'change' }]
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listAgent(queryParam.value);
    agentList.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
};

const loadOptions = async () => {
  const [ws, models, tools, mcps, kbs, agents] = await Promise.all([
    myWorkspaces(),
    listModel({ pageNum: 1, pageSize: 100 }),
    listTool({ pageNum: 1, pageSize: 100 }),
    listMcpServer({ pageNum: 1, pageSize: 100, status: '0' }),
    listKnowledge({ pageNum: 1, pageSize: 100 }),
    listAgent({ pageNum: 1, pageSize: 200 })
  ]);
  workspaceOptions.value = ws.data || [];
  modelOptions.value = models.data?.rows || [];
  toolOptions.value = tools.data?.rows || [];
  mcpOptions.value = mcps.data?.rows || [];
  knowledgeOptions.value = kbs.data?.rows || [];
  agentOptions.value = agents.data?.rows || [];
  await loadPromptTemplates();
};

/** 协作成员候选:当前编辑 Agent 自身除外 */
const memberOptions = computed(() =>
  agentOptions.value.filter((a) => String(a.id) !== String(form.value.id))
);

const handleQuery = () => {
  queryParam.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParam.value = { pageNum: 1, pageSize: 10, keyword: '' };
  sortField.value = '';
  handleQuery();
};

const handleAdd = async () => {
  isEdit.value = false;
  form.value = { ...initForm };
  suggestedText.value = '';
  await loadOptions();
  dialog.title = '新建 Agent';
  dialog.visible = true;
};

/** 推荐问题:JSON 数组 → 每行一项的文本(表单编辑用) */
const parseQuestions = (raw?: string): string => {
  if (!raw) return '';
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr.filter((q: unknown) => typeof q === 'string').join('\n') : '';
  } catch {
    return '';
  }
};

/** 推荐问题:每行一项的文本 → JSON 数组字符串(最多 10 条) */
const questionsToJson = (text: string): string | undefined => {
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean).slice(0, 10);
  return lines.length ? JSON.stringify(lines) : undefined;
};

const handleEdit = async (row: AgentVO) => {
  isEdit.value = true;
  await loadOptions();
  const res = await getAgent(row.id);
  form.value = {
    ...res.data!,
    outputSchema: res.data!.outputSchema || '',
    memoryEnabled: res.data!.memoryEnabled === '1',
    workflowTriggerMode: res.data!.workflowTriggerMode || '0',
    teamMode: res.data!.teamMode || 'none',
    teamIds: res.data!.teamIds || []
  };
  suggestedText.value = parseQuestions(res.data!.suggestedQuestions);
  dialog.title = '编辑 Agent';
  dialog.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return;
    if (form.value.outputSchema?.trim() && form.value.teamMode !== 'none' && form.value.teamIds?.length) {
      proxy?.$modal.msgWarning('结构化输出与团队协作暂不支持同时启用');
      return;
    }
    const payload: AgentForm = {
      ...form.value,
      outputSchema: form.value.outputSchema?.trim() || undefined,
      suggestedQuestions: questionsToJson(suggestedText.value),
      memoryEnabled: form.value.memoryEnabled === true,
      workflowTriggerMode: form.value.workflowTriggerMode || '0',
      teamIds: form.value.teamMode !== 'none' ? form.value.teamIds : []
    };
    dialog.saving = true;
    try {
      if (isEdit.value) {
        await updateAgent(payload);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addAgent(payload);
        proxy?.$modal.msgSuccess('新建成功');
      }
      dialog.visible = false;
      getList();
    } finally {
      dialog.saving = false;
    }
  });
};

const handlePublish = async (row: AgentVO) => {
  try {
    if (!row.modelName) {
      await proxy?.$modal.confirm(`该 Agent 尚未绑定模型,发布前需要先绑定模型。是否前往编辑?`);
      handleEdit(row);
      return;
    }
    // P1:发布前先展示门禁预检结果;后端 changeStatus 会再次执行门禁,前端无法绕过
    const gate = await previewGate(row.id);
    const tip = gate.data?.passed
      ? `门禁预检通过(${gate.data.reason})。发布将冻结一份不可变配置快照。`
      : `门禁预检未通过:${gate.data?.reason}。继续发布会被后端阻断,可先处理或申请豁免。`;
    await proxy?.$modal.confirm(`${tip}确认发布 Agent「${row.name}」吗?`);
    await changeAgentStatus(row.id, '1');
    proxy?.$modal.alertSuccess(`Agent「${row.name}」已发布,可在聊天对话中使用。如需上架智能体市场,请前往「智能体市场 → 我的发布」提交发布`);
    getList();
  } catch (e) {
    // 用户取消
  }
};

const handleOffline = async (row: AgentVO) => {
  try {
    await proxy?.$modal.confirm(`确认停用 Agent「${row.name}」吗?停用后不可创建新会话`);
    await changeAgentStatus(row.id, '2');
    proxy?.$modal.msgSuccess('已停用');
    getList();
  } catch (e) {
    // 用户取消
  }
};

const handleWorkflow = (row: AgentVO) => {
  router.push(`/agent/workflow/${row.id}`);
};

/** 克隆 Agent:复制全部配置到同空间新 Agent,状态为草稿 */
const handleClone = async (row: AgentVO) => {
  try {
    await proxy?.$modal.confirm(`确认克隆 Agent「${row.name}」吗?将在同空间下创建一份配置相同的副本`);
    const res = await cloneAgent(row.id);
    proxy?.$modal.msgSuccess(`克隆成功,新 Agent ID:${res.data}`);
    getList();
  } catch (e) {
    // 用户取消或接口失败
  }
};

const handleDelete = async (row: AgentVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除 Agent「${row.name}」吗?`);
    await delAgent(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    getList();
  } catch (e) {
    // 用户取消
  }
};

onMounted(() => {
  getList();
});
</script>

<style scoped>
.form-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
