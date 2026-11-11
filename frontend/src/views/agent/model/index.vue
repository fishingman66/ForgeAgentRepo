<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="模型名称">
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
          <div class="table-heading"><h3>模型配置</h3></div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:model:add']" type="primary" plain icon="Plus" @click="handleAdd">新增模型</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="modelList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="范围" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.workspaceId ? 'warning' : 'success'" effect="plain">
              {{ scope.row.workspaceId ? '空间私有' : '平台共享' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" prop="name" min-width="140" show-overflow-tooltip />
        <el-table-column label="提供商" width="110" align="center">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ providerLabel(scope.row.provider) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型标识" prop="modelName" min-width="140" show-overflow-tooltip />
        <el-table-column label="图片输入" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.supportVision === '1' ? 'success' : 'info'" effect="plain">
              {{ scope.row.supportVision === '1' ? '支持' : '不支持' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="API地址" prop="baseUrl" min-width="160" show-overflow-tooltip />
        <el-table-column label="密钥" width="130" show-overflow-tooltip>
          <template #default="scope">
            <span>{{ scope.row.apiKey || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="温度" prop="temperature" width="70" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as ModelVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="120" show-overflow-tooltip />
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:model:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as ModelVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:model:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as ModelVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新增/编辑模型 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <ResourceScopeFields v-model="form" />
        <el-form-item label="模型名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,全局唯一" maxlength="50" />
        </el-form-item>
        <el-form-item label="提供商" prop="provider">
          <el-select v-model="form.provider" placeholder="请选择提供商" style="width: 100%">
            <el-option label="OpenAI" value="openai" />
            <el-option label="Anthropic" value="anthropic" />
            <el-option label="DashScope(通义)" value="dashscope" />
            <el-option label="Ollama(本地)" value="ollama" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="API地址" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="如 https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item label="API密钥" prop="apiKey">
          <el-input v-model="form.apiKey" type="password" show-password :placeholder="isEdit ? '留空表示不修改' : '请输入 API Key'" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="模型标识" prop="modelName">
          <el-input v-model="form.modelName" placeholder="如 gpt-4o / qwen-plus / claude-3-5-sonnet" />
        </el-form-item>
        <el-form-item label="图片输入" prop="supportVision">
          <el-switch v-model="form.supportVision" active-value="1" inactive-value="0" active-text="支持" inactive-text="不支持" inline-prompt />
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
        <el-form-item label="上下文窗口" prop="contextWindow">
          <el-input-number v-model="form.contextWindow" :min="1024" :max="10000000" :step="1024" placeholder="留空则不展示会话上下文剩余" style="width: 100%" controls-position="right" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="输入单价" prop="priceInput">
              <el-input-number v-model="form.priceInput" :min="0" :precision="4" :step="0.1" placeholder="每百万token" style="width: 100%" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="输出单价" prop="priceOutput">
              <el-input-number v-model="form.priceOutput" :min="0" :precision="4" :step="0.1" placeholder="每百万token" style="width: 100%" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="币种" prop="priceCurrency">
              <el-select v-model="form.priceCurrency" style="width: 100%">
                <el-option label="USD" value="USD" />
                <el-option label="CNY" value="CNY" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentModel">
import { addModel, changeModelStatus, delModel, getModel, listModel, updateModel } from '@/api/agent/model';
import type { ModelForm, ModelQuery, ModelVO } from '@/api/agent/model/types';
import ResourceScopeFields from '@/views/agent/components/ResourceScopeFields.vue';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const modelList = ref<ModelVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:model:edit']);

const queryParam = ref<ModelQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const providerMap: Record<string, string> = {
  openai: 'OpenAI',
  anthropic: 'Anthropic',
  dashscope: '通义千问',
  ollama: 'Ollama',
  custom: '自定义'
};
const providerLabel = (p: string) => providerMap[p] || p;

const dialog = ref({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: ModelForm = { id: undefined, name: '', provider: 'openai', baseUrl: '', apiKey: '', modelName: '', supportVision: '0', temperature: 0.7, maxTokens: 4096, contextWindow: null, priceInput: null, priceOutput: null, priceCurrency: 'USD', remark: '' };
const form = ref<ModelForm>({ ...initForm });

const rules = {
  name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
  modelName: [{ required: true, message: '请输入模型标识', trigger: 'blur' }],
  maxTokens: [{ required: true, message: '请输入最大 tokens', trigger: 'blur' }]
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listModel(queryParam.value);
    modelList.value = res.data?.rows || [];
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
  queryParam.value = { pageNum: 1, pageSize: 10, keyword: '', status: '' };
  handleQuery();
};

const handleAdd = () => {
  isEdit.value = false;
  form.value = { ...initForm };
  dialog.value.title = '新增模型';
  dialog.value.visible = true;
};

const handleEdit = (row: ModelVO) => {
  isEdit.value = true;
  form.value = { ...row, apiKey: '', supportVision: row.supportVision ?? '0' };
  dialog.value.title = '编辑模型';
  dialog.value.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return;
    dialog.value.saving = true;
    try {
      if (isEdit.value) {
        await updateModel(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addModel(form.value);
        proxy?.$modal.msgSuccess('新增成功');
      }
      dialog.value.visible = false;
      getList();
    } finally {
      dialog.value.saving = false;
    }
  });
};

const handleStatusChange = async (row: ModelVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  const text = next === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm(`确认要${text}模型「${row.name}」吗?`);
    await changeModelStatus(row.id, next);
    proxy?.$modal.msgSuccess(`${text}成功`);
    getList();
  } catch (e) {
    // 用户取消或失败,不改变状态
  }
};

const handleDelete = async (row: ModelVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除模型「${row.name}」吗?`);
    await delModel(row.id);
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