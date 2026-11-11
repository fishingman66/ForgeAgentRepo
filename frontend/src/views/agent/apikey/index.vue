<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="工作空间">
          <el-select v-model="queryParam.workspaceId" placeholder="全部空间" clearable filterable style="width: 180px" @change="handleQuery">
            <el-option v-for="ws in workspaceOptions" :key="ws.id" :label="ws.name" :value="String(ws.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="queryParam.name" placeholder="Key 名称" clearable style="width: 160px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParam.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" value="0" />
            <el-option label="已吊销" value="1" />
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
            <h3>API 密钥</h3>
            <span class="heading-tip">Key 明文仅创建时展示一次,列表始终脱敏;吊销后立即失效</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:apikey:add']" type="primary" plain icon="Plus" @click="handleAdd">创建 Key</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="keyList" class="data-table">
        <el-table-column label="名称" prop="name" min-width="140" show-overflow-tooltip />
        <el-table-column label="工作空间" prop="workspaceName" min-width="140" show-overflow-tooltip />
        <el-table-column label="Key" prop="maskedKey" min-width="180" show-overflow-tooltip>
          <template #default="scope">
            <code>{{ scope.row.maskedKey }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'danger'" size="small">
              {{ scope.row.status === '0' ? '正常' : '已吊销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近使用" width="170" align="center">
          <template #default="scope">{{ scope.row.lastUsedTime || '从未使用' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="110" align="center" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === '0'"
              v-hasPermi="['agent:apikey:revoke']"
              size="small" text type="danger" icon="CircleClose"
              @click="handleRevoke(scope.row as ApiKeyVO)"
            >吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 创建 Key -->
    <el-dialog v-model="create.visible" title="创建 API Key" width="440px" destroy-on-close :close-on-click-modal="false">
      <el-form ref="createFormRef" :model="create.form" :rules="createRules" label-width="90px">
        <el-form-item label="工作空间" prop="workspaceId">
          <el-select v-model="create.form.workspaceId" placeholder="选择所属空间" filterable style="width: 100%">
            <el-option v-for="ws in workspaceOptions" :key="ws.id" :label="ws.name" :value="String(ws.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="create.form.name" maxlength="50" placeholder="便于识别的用途说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.visible = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 明文展示(仅一次) -->
    <el-dialog v-model="plain.visible" title="Key 创建成功" width="520px" :close-on-click-modal="false" :show-close="false">
      <el-alert type="warning" :closable="false" title="明文仅此一次展示,关闭后将无法再次查看,请立即复制并妥善保存。" style="margin-bottom: 14px" />
      <el-input v-model="plain.key" readonly>
        <template #append>
          <el-button icon="CopyDocument" @click="copyKey">复制</el-button>
        </template>
      </el-input>
      <template #footer>
        <el-button type="primary" @click="plain.visible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentApiKey">
import { addApiKey, listApiKey, revokeApiKey } from '@/api/agent/apikey';
import type { ApiKeyForm, ApiKeyQuery, ApiKeyVO } from '@/api/agent/apikey/types';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const keyList = ref<ApiKeyVO[]>([]);
const total = ref(0);
const workspaceOptions = ref<WorkspaceVO[]>([]);

const queryParam = ref<ApiKeyQuery>({ pageNum: 1, pageSize: 10, workspaceId: '', name: '', status: '' });

const getList = async () => {
  loading.value = true;
  try {
    const res = await listApiKey(queryParam.value);
    keyList.value = res.data?.rows || [];
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
  queryParam.value = { pageNum: 1, pageSize: 10, workspaceId: '', name: '', status: '' };
  handleQuery();
};

// ============ 创建 ============
const createFormRef = ref();
const create = reactive<{ visible: boolean; saving: boolean; form: ApiKeyForm }>({
  visible: false,
  saving: false,
  form: { workspaceId: undefined, name: '' }
});
const createRules = {
  workspaceId: [{ required: true, message: '请选择工作空间', trigger: 'change' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
};

const plain = reactive({ visible: false, key: '' });

const handleAdd = () => {
  create.form = { workspaceId: queryParam.value.workspaceId || undefined, name: '' };
  create.visible = true;
};

const submitCreate = () => {
  createFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    create.saving = true;
    try {
      const res = await addApiKey(create.form);
      create.visible = false;
      plain.key = res.data?.plainKey || '';
      plain.visible = true;
      getList();
    } finally {
      create.saving = false;
    }
  });
};

const copyKey = async () => {
  try {
    await navigator.clipboard.writeText(plain.key);
    proxy?.$modal.msgSuccess('已复制');
  } catch {
    proxy?.$modal.msgWarning('复制失败,请手动选择复制');
  }
};

const handleRevoke = async (row: ApiKeyVO) => {
  try {
    await proxy?.$modal.confirm(`确认吊销 Key「${row.name}」吗?吊销后立即失效且不可恢复。`);
    await revokeApiKey(row.id);
    proxy?.$modal.msgSuccess('已吊销');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

onMounted(async () => {
  getList();
  const res = await myWorkspaces();
  workspaceOptions.value = res.data || [];
});
</script>
