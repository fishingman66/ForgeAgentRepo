<template>
  <el-drawer :model-value="modelValue" title="工作流 API 端点" size="620px" @update:model-value="(v: boolean) => emit('update:modelValue', v)" @open="onOpen">
    <div v-if="agentId">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="把已编排的工作流暴露为外部可调用的同步 HTTP 端点;鉴权挂空间 API 密钥(Authorization: Bearer ak-...),支持 Idempotency-Key 防重"
        style="margin-bottom: 12px"
      />

      <!-- 列表 -->
      <el-table v-loading="loading" :data="endpointList" size="small" border>
        <el-table-column label="编码" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <el-text size="small" type="primary" tag="code">{{ row.endpointCode }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="密钥" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.maskedKey || '-' }}</template>
        </el-table-column>
        <el-table-column label="限流/超时" width="110" align="center">
          <template #default="{ row }">{{ row.rateLimit }}/分 · {{ row.timeoutSeconds }}s</template>
        </el-table-column>
        <el-table-column label="状态" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === '0' ? 'success' : 'info'">
              {{ row.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showInvokeExample((row as WorkflowEndpointVO))">调用示例</el-button>
            <el-button link type="warning" size="small" @click="toggleStatus((row as WorkflowEndpointVO))">{{ row.status === '0' ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" size="small" @click="handleDelete((row as WorkflowEndpointVO))">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无端点" :image-size="50" /></template>
      </el-table>

      <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
        <el-button type="primary" size="small" icon="Plus" @click="openForm()">创建端点</el-button>
        <pagination v-model:page="page" v-model:limit="size" :total="total" layout="prev, pager, next" @pagination="loadList" />
      </div>

      <!-- 创建/编辑表单 -->
      <el-dialog v-model="formVisible" :title="form.id ? '编辑端点' : '创建端点'" width="460px" append-to-body>
        <el-form label-width="90px" size="small">
          <el-form-item label="端点编码">
            <el-input v-model="form.endpointCode" :disabled="!!form.id" placeholder="小写字母/数字/连字符,如 order-query" />
            <div class="tip">调用路径:POST /workflow-api/{endpointCode},创建后不可修改</div>
          </el-form-item>
          <el-form-item label="名称">
            <el-input v-model="form.name" maxlength="100" placeholder="端点用途说明(可选)" />
          </el-form-item>
          <el-form-item label="API 密钥">
            <el-select v-model="form.apiKeyId" placeholder="选择同空间已启用的密钥" clearable filterable style="width: 100%">
              <el-option v-for="k in apiKeyOptions" :key="k.id" :label="k.name" :value="k.id" />
            </el-select>
            <div class="tip">密钥明文只在「API 密钥」页创建时展示一次;不绑定则接受同空间任意有效密钥</div>
          </el-form-item>
          <el-form-item label="限流">
            <el-input-number v-model="form.rateLimit" :min="1" :max="6000" style="width: 100%" />
            <div class="tip">每分钟调用上限</div>
          </el-form-item>
          <el-form-item label="超时(秒)">
            <el-input-number v-model="form.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
            <div class="tip">同步执行最长等待;LLM 节点本身有 300 秒上限</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="formVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
        </template>
      </el-dialog>

      <!-- 调用示例 -->
      <el-dialog v-model="exampleVisible" title="调用示例" width="560px" append-to-body>
        <pre class="curl-example">{{ exampleText }}</pre>
      </el-dialog>
    </div>
  </el-drawer>
</template>

<script setup lang="ts" name="WorkflowEndpointDrawer">
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  listWorkflowEndpoint, addWorkflowEndpoint, updateWorkflowEndpoint,
  changeWorkflowEndpointStatus, delWorkflowEndpoint
} from '@/api/agent/workflow-endpoint';
import type { WorkflowEndpointVO, WorkflowEndpointForm } from '@/api/agent/workflow-endpoint';
import { listApiKey } from '@/api/agent/apikey';
import type { ApiKeyVO } from '@/api/agent/apikey/types';

const props = defineProps<{ modelValue: boolean; agentId: number | string }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

const loading = ref(false);
const endpointList = ref<WorkflowEndpointVO[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(10);
const apiKeyOptions = ref<ApiKeyVO[]>([]);

const formVisible = ref(false);
const saving = ref(false);
const exampleVisible = ref(false);
const exampleText = ref('');
const form = ref<WorkflowEndpointForm>(defaultForm());

function defaultForm(): WorkflowEndpointForm {
  return {
    agentId: props.agentId,
    endpointCode: '',
    name: '',
    apiKeyId: null,
    rateLimit: 60,
    timeoutSeconds: 60,
    status: '0'
  };
}

const onOpen = () => {
  page.value = 1;
  loadList();
  loadApiKeys();
};

const loadList = async () => {
  loading.value = true;
  try {
    const res = await listWorkflowEndpoint(props.agentId, page.value, size.value);
    endpointList.value = res.data?.rows || [];
    total.value = Number(res.data?.total || 0);
  } finally {
    loading.value = false;
  }
};

const loadApiKeys = async () => {
  try {
    const res = await listApiKey({ pageNum: 1, pageSize: 100, status: '0' });
    apiKeyOptions.value = res.data?.rows || [];
  } catch {
    apiKeyOptions.value = [];
  }
};

const openForm = () => {
  form.value = defaultForm();
  formVisible.value = true;
};

const saveForm = async () => {
  if (!form.value.endpointCode?.trim()) {
    ElMessage.warning('端点编码不能为空');
    return;
  }
  saving.value = true;
  try {
    if (form.value.id) {
      await updateWorkflowEndpoint(form.value);
      ElMessage.success('端点已更新');
    } else {
      await addWorkflowEndpoint(form.value);
      ElMessage.success('端点已创建,可在列表中查看调用示例');
    }
    formVisible.value = false;
    await loadList();
  } finally {
    saving.value = false;
  }
};

const toggleStatus = async (row: WorkflowEndpointVO) => {
  const next = row.status === '0' ? '1' : '0';
  await changeWorkflowEndpointStatus(row.id, next);
  ElMessage.success(next === '0' ? '端点已启用' : '端点已停用');
  await loadList();
};

const handleDelete = async (row: WorkflowEndpointVO) => {
  try {
    await ElMessageBox.confirm(`确定删除端点 ${row.endpointCode} 吗?外部调用将立即失效。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除'
    });
  } catch {
    return;
  }
  await delWorkflowEndpoint([row.id]);
  ElMessage.success('端点已删除');
  await loadList();
};

const showInvokeExample = (row: WorkflowEndpointVO) => {
  const origin = window.location.origin;
  exampleText.value = [
    `POST ${origin}/workflow-api/${row.endpointCode}`,
    'Authorization: Bearer ak-xxxxxxxx',
    '(可选) Idempotency-Key: <任意唯一值,防重复投递>',
    'Content-Type: application/json',
    '',
    '{"query": "你的问题"}',
    '',
    '# 每个键注入为 start.<键> 变量;返回 {runId, status, output, message, costMs}'
  ].join('\n');
  exampleVisible.value = true;
};
</script>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}
.curl-example {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
