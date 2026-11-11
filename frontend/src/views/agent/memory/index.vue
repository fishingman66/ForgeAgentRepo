<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="Agent">
          <el-select v-model="queryParam.agentId" placeholder="选择 Agent" filterable style="width: 240px" @change="handleAgentChange">
            <el-option v-for="a in agentOptions" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="记忆内容" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-tabs v-model="activeTab" class="memory-tabs" style="margin-top: 10px" @tab-change="handleTabChange">
      <!-- ========== Tab 1: 记忆列表 ========== -->
      <el-tab-pane label="记忆列表" name="list">
        <el-card shadow="hover">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>长期记忆</h3>
                <span class="heading-tip">开启「长期记忆」的 Agent 在对话后自动提取要点,并在后续对话中注入;也可手动维护共享记忆</span>
              </div>
              <div class="toolbar-actions">
                <el-input
                  v-model="semanticQuery"
                  placeholder="语义检索:换个问法也能找到"
                  clearable
                  style="width: 220px; margin-right: 8px"
                  @keyup.enter="handleSemanticSearch"
                />
                <el-button type="success" plain icon="Search" :disabled="!queryParam.agentId" @click="handleSemanticSearch">语义检索</el-button>
                <el-button v-hasPermi="['agent:memory:add']" type="primary" icon="Plus" :disabled="!queryParam.agentId" @click="openAdd">新增记忆</el-button>
                <el-button v-hasPermi="['agent:memory:remove']" type="danger" plain icon="Delete" :disabled="!queryParam.agentId" @click="handleClear">清空全部</el-button>
              </div>
            </div>
          </template>

          <el-alert
            v-if="semanticMode"
            :title="`语义检索「${semanticQuery}」的结果(向量不可用时回退评分排序),共 ${semanticList.length} 条`"
            type="info"
            :closable="true"
            style="margin-bottom: 8px"
            @close="exitSemantic"
          />
          <el-table v-loading="loading" border :data="semanticMode ? semanticList : memoryList" class="data-table" @selection-change="handleSelectionChange">
            <el-table-column type="selection" width="55" align="center" />
            <el-table-column label="ID" prop="id" width="80" align="center" />
            <el-table-column label="记忆内容" prop="content" min-width="320" show-overflow-tooltip />
            <el-table-column label="归属" width="110" align="center">
              <template #default="scope">
                <el-tag v-if="scope.row.userId" size="small" type="primary" effect="plain">用户私有</el-tag>
                <el-tag v-else size="small" type="success" effect="plain">共享</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="110" align="center">
              <template #default="scope">
                <el-tag v-if="scope.row.sourceSessionId" size="small" type="warning" effect="plain">对话提取</el-tag>
                <el-tag v-else size="small" type="info" effect="plain">手动添加</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
            <el-table-column label="操作" width="100" align="center" fixed="right">
              <template #default="scope">
                <el-button v-hasPermi="['agent:memory:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as MemoryVO)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <pagination
            v-if="!semanticMode"
            v-model:page="queryParam.pageNum"
            v-model:limit="queryParam.pageSize"
            :total="total"
            @pagination="getList"
          />
        </el-card>
      </el-tab-pane>

      <!-- ========== Tab 2: 记忆图谱 ========== -->
      <el-tab-pane label="记忆图谱" name="graph">
        <el-card shadow="hover">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>记忆图谱</h3>
                <span class="heading-tip">从记忆中抽取的实体关系;图谱增强注入与检索扩展都消费这份数据</span>
              </div>
              <div class="toolbar-actions">
                <el-button size="small" @click="loadGraph">刷新</el-button>
                <el-button size="small" type="primary" plain :disabled="!queryParam.agentId" @click="handleRebuild">补建存量记忆</el-button>
              </div>
            </div>
          </template>
          <h4 class="kg-subtitle">实体(按显著度)</h4>
          <div class="kg-nodes">
            <el-tag v-for="n in graphNodes" :key="n.key" size="small" class="kg-node-tag">{{ n.name }}({{ n.salience }})</el-tag>
            <el-empty v-if="graphNodes.length === 0" description="暂无实体,先做一次「补建」或对话沉淀" :image-size="60" />
          </div>
          <h4 class="kg-subtitle">关系边(按权重)</h4>
          <el-table v-loading="graphLoading" border :data="graphEdges" size="small">
            <el-table-column label="关系" min-width="240">
              <template #default="scope">{{ scope.row.sourceName }} —{{ scope.row.relation }}→ {{ scope.row.targetName }}</template>
            </el-table-column>
            <el-table-column label="权重" prop="weight" width="90" align="center" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ========== Tab 3: 治理 ========== -->
      <el-tab-pane label="治理与遗忘" name="governance">
        <el-card shadow="hover">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>治理与遗忘(L5)</h3>
                <span class="heading-tip">消息归档 / TTL 过期 / 配额淘汰由治理任务按 memory.governance.* 配置执行;这里可手动触发与预览</span>
              </div>
              <div class="toolbar-actions">
                <el-button v-hasPermi="['agent:memory:remove']" size="small" type="warning" plain @click="handleRunGovernance">手动执行一次治理</el-button>
              </div>
            </div>
          </template>

          <h4 class="kg-subtitle">注入预览(所见即坐席模型所得)</h4>
          <div class="gov-preview">
            <el-input v-model="previewQuery" placeholder="模拟本轮用户问题(用于语义召回)" style="width: 320px" @keyup.enter="handlePreview" />
            <el-button type="primary" size="small" @click="handlePreview">预览注入块</el-button>
          </div>
          <pre v-if="previewBlock" class="gov-block">{{ previewBlock }}</pre>

          <el-divider />
          <h4 class="kg-subtitle">用户级遗忘(级联删除:记忆→图谱→归档,不可恢复)</h4>
          <div class="gov-preview">
            <el-input v-model="forgetUserId" placeholder="用户 ID(数字)" style="width: 220px" />
            <el-button v-hasPermi="['agent:memory:remove']" type="danger" plain :disabled="!queryParam.agentId" @click="handleForget">执行遗忘</el-button>
            <span class="heading-tip">平台级共享记忆不属于任何用户,不在遗忘范围</span>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts" name="AgentMemory">
import { ElMessageBox } from 'element-plus';
import {
  addMemory,
  clearMemory,
  delMemory,
  forgetMemoryUser,
  listMemory,
  listMemoryGraphEdges,
  listMemoryGraphNodes,
  previewMemoryBlock,
  rebuildMemoryGraph,
  runMemoryGovernance,
  searchMemory
} from '@/api/agent/memory';
import type { MemoryForm, MemoryQuery, MemorySearchHit, MemoryVO } from '@/api/agent/memory/types';
import type { MemoryGraphEdge, MemoryGraphNode } from '@/api/agent/memory/types';
import { listAgent } from '@/api/agent/agent';
import type { AgentVO } from '@/api/agent/agent/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const activeTab = ref('list');
const loading = ref(false);
const memoryList = ref<MemoryVO[]>([]);
const total = ref(0);
const agentOptions = ref<AgentVO[]>([]);
const ids = ref<Array<string | number>>([]);

const queryParam = ref<MemoryQuery>({ pageNum: 1, pageSize: 10, agentId: '', keyword: '' });

/** 语义检索状态:有结果时列表区临时展示检索结果,关闭提示后回到分页列表 */
const semanticMode = ref(false);
const semanticQuery = ref('');
const semanticList = ref<MemorySearchHit[]>([]);

/** 图谱状态 */
const graphLoading = ref(false);
const graphNodes = ref<MemoryGraphNode[]>([]);
const graphEdges = ref<MemoryGraphEdge[]>([]);

/** 治理状态 */
const previewQuery = ref('');
const previewBlock = ref('');
const forgetUserId = ref('');

const getList = async () => {
  if (!queryParam.value.agentId) {
    memoryList.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const res = await listMemory(queryParam.value);
    memoryList.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParam.value.pageNum = 1;
  semanticMode.value = false;
  getList();
};

const resetQuery = () => {
  queryParam.value = { pageNum: 1, pageSize: 10, agentId: queryParam.value.agentId, keyword: '' };
  handleQuery();
};

const exitSemantic = () => {
  semanticMode.value = false;
  semanticQuery.value = '';
};

const handleSemanticSearch = async () => {
  if (!queryParam.value.agentId || !semanticQuery.value.trim()) {
    proxy?.$modal.msgWarning('先选择 Agent,并输入检索词');
    return;
  }
  loading.value = true;
  try {
    const res = await searchMemory(queryParam.value.agentId, semanticQuery.value.trim(), undefined, 10);
    semanticList.value = res.data || [];
    semanticMode.value = true;
  } finally {
    loading.value = false;
  }
};

const handleSelectionChange = (selection: MemoryVO[]) => {
  ids.value = selection.map((item) => item.id);
};

const openAdd = async () => {
  try {
    const { value } = await ElMessageBox.prompt('输入要长期记住的信息,将在该 Agent 后续对话中注入', '新增共享记忆', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '例如:用户偏好简洁回复,不需要寒暄',
      inputValidator: (v: string) => (v && v.trim().length >= 1 && v.length <= 1000) || '内容须为 1-1000 字符'
    });
    const form: MemoryForm = { agentId: queryParam.value.agentId, content: value.trim() };
    await addMemory(form);
    proxy?.$modal.msgSuccess('新增成功');
    getList();
  } catch (e) {
    // 用户取消
  }
};

const handleDelete = async (row: MemoryVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除该条记忆吗?`);
    await delMemory(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    semanticMode.value = false;
    getList();
  } catch (e) {
    // 用户取消
  }
};

const handleClear = async () => {
  try {
    await proxy?.$modal.confirm(`确认清空该 Agent 的全部记忆吗?此操作不可恢复`);
    await clearMemory(queryParam.value.agentId);
    proxy?.$modal.msgSuccess('已清空');
    semanticMode.value = false;
    getList();
  } catch (e) {
    // 用户取消
  }
};

const loadGraph = async () => {
  if (!queryParam.value.agentId) {
    return;
  }
  graphLoading.value = true;
  try {
    const [nodesRes, edgesRes] = await Promise.all([
      listMemoryGraphNodes(queryParam.value.agentId, undefined, 100),
      listMemoryGraphEdges(queryParam.value.agentId, undefined, 200)
    ]);
    graphNodes.value = nodesRes.data || [];
    graphEdges.value = edgesRes.data || [];
  } finally {
    graphLoading.value = false;
  }
};

const handleRebuild = async () => {
  try {
    await proxy?.$modal.confirm('补建会把该 Agent 的存量记忆分批送模型抽取三元组,会消耗模型调用,确认执行?');
  } catch {
    return;
  }
  await rebuildMemoryGraph(queryParam.value.agentId);
  proxy?.$modal.msgSuccess('已投递后台补建,稍后刷新查看');
};

const handlePreview = async () => {
  if (!queryParam.value.agentId || !previewQuery.value.trim()) {
    proxy?.$modal.msgWarning('先选择 Agent,并输入模拟问题');
    return;
  }
  const res = await previewMemoryBlock(queryParam.value.agentId, previewQuery.value.trim());
  previewBlock.value = res.data || '(本轮没有注入任何记忆)';
};

const handleForget = async () => {
  if (!queryParam.value.agentId || !forgetUserId.value.trim()) {
    proxy?.$modal.msgWarning('先选择 Agent,并输入要遗忘的用户 ID');
    return;
  }
  try {
    await proxy?.$modal.confirm(`将级联删除该用户在此 Agent 下的记忆、图谱与归档数据,不可恢复。确认遗忘用户 ${forgetUserId.value}?`);
  } catch {
    return;
  }
  try {
    const res = await forgetMemoryUser(queryParam.value.agentId, forgetUserId.value.trim(), 'FORGET');
    proxy?.$modal.msgSuccess(`已遗忘,删除记忆 ${res.data ?? 0} 条,图谱与归档已级联清理`);
    semanticMode.value = false;
    getList();
  } catch {
    proxy?.$modal.msgError('遗忘失败');
  }
};

const handleRunGovernance = async () => {
  try {
    const res = await runMemoryGovernance();
    const m = res.data || {};
    proxy?.$modal.msgSuccess(`治理完成:消息归档 ${m.archivedMessages ?? 0} 条 / TTL 归档 ${m.ttlArchived ?? 0} 条 / 配额淘汰 ${m.quotaEvicted ?? 0} 条`);
  } catch {
    proxy?.$modal.msgError('治理未启用或执行失败,检查 memory.governance.enabled');
  }
};

const handleAgentChange = () => {
  semanticMode.value = false;
  handleQuery();
  if (activeTab.value === 'graph') {
    loadGraph();
  }
};

const handleTabChange = (name: string | number) => {
  if (name === 'graph') {
    loadGraph();
  }
};

const loadAgents = async () => {
  const res = await listAgent({ pageNum: 1, pageSize: 200 });
  agentOptions.value = res.data?.rows || [];
  if (agentOptions.value.length > 0) {
    queryParam.value.agentId = agentOptions.value[0].id;
  }
};

onMounted(async () => {
  await loadAgents();
  getList();
});
</script>

<style scoped>
.memory-tabs :deep(.el-tabs__content) {
  padding-top: 4px;
}
.toolbar-shell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}
.table-heading h3 {
  margin: 0 0 4px;
}
.heading-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.toolbar-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.kg-subtitle {
  margin: 8px 0;
  font-weight: 600;
}
.kg-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}
.kg-node-tag {
  margin: 0;
}
.gov-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.gov-block {
  margin: 0;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  max-height: 320px;
  overflow: auto;
}
</style>
