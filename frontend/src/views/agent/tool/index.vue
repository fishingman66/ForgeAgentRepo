<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="工具名称">
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
          <div class="table-heading"><h3>工具注册</h3></div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:tool:add']" type="primary" plain icon="Plus" @click="handleAdd">注册工具</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="toolList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="范围" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.workspaceId ? 'warning' : 'success'" effect="plain">
              {{ scope.row.workspaceId ? '空间私有' : '平台共享' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" prop="name" min-width="130" show-overflow-tooltip />
        <el-table-column label="描述" prop="description" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="toolTypeTagType(scope.row.toolType)">
              {{ toolTypeLabel(scope.row.toolType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="配置" min-width="200" show-overflow-tooltip>
          <template #default="scope">
            <span v-if="scope.row.toolType === 'http'">{{ scope.row.httpMethod }} {{ scope.row.httpUrl }}</span>
            <span v-else-if="scope.row.toolType === 'sandbox'">{{ scope.row.sandboxImage || 'python:3.11-slim' }} / {{ scope.row.sandboxMemory || '128m' }} / {{ scope.row.sandboxCpus || '0.5' }}核 / {{ scope.row.sandboxTimeout || 30 }}s</span>
            <span v-else-if="scope.row.toolType === 'sql'">数据源: {{ dsName(scope.row.datasourceId) }}</span>
            <span v-else style="color: var(--el-text-color-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as ToolVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:tool:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as ToolVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:tool:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as ToolVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 注册/编辑工具 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="600px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <ResourceScopeFields v-model="form" />
        <el-form-item label="工具名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,全局唯一" maxlength="50" />
        </el-form-item>
        <el-form-item label="工具描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="工具类型" prop="toolType">
          <el-radio-group v-model="form.toolType">
            <el-radio value="builtin">内置工具</el-radio>
            <el-radio value="http">HTTP 接口</el-radio>
            <el-radio value="sandbox">代码沙箱</el-radio>
            <el-radio value="sql">问数查询</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.toolType === 'sql'">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
            title="问数工具在绑定数据源上执行模型生成的只读 SELECT 查询:自动强制行数上限、语句超时,结果以表格和图表随回复展示" />
          <el-form-item label="绑定数据源" prop="datasourceId">
            <el-select v-model="form.datasourceId" placeholder="请选择数据源" style="width: 100%">
              <el-option v-for="ds in dsOptions" :key="ds.id" :label="`${ds.name}(${ds.dbName})${ds.status === '1' ? ' - 已停用' : ''}`" :value="ds.id" :disabled="ds.status === '1'" />
            </el-select>
          </el-form-item>
        </template>
        <template v-if="form.toolType === 'http'">
          <el-form-item label="HTTP方法" prop="httpMethod">
            <el-select v-model="form.httpMethod" placeholder="请选择方法" style="width: 180px">
              <el-option v-for="m in ['GET', 'POST', 'PUT', 'DELETE']" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="接口地址" prop="httpUrl">
            <el-input v-model="form.httpUrl" placeholder="https://example.com/api/xxx" />
          </el-form-item>
          <el-form-item label="请求头" prop="httpHeaders">
            <el-input v-model="form.httpHeaders" type="textarea" :rows="3" placeholder='JSON,如 {"Authorization":"Bearer xxx"}' />
          </el-form-item>
          <el-form-item label="请求参数" prop="httpParams">
            <el-input v-model="form.httpParams" type="textarea" :rows="3" placeholder="JSON 模板,如 {&quot;q&quot;:&quot;{{ '{{query}}' }}&quot;}" />
          </el-form-item>
        </template>
        <template v-if="form.toolType === 'sandbox'">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
            title="沙箱在一次性 Docker 容器中执行 Python 代码:无网络、进程数受限、输出截断,容器用完即销毁" />
          <el-form-item label="沙箱镜像" prop="sandboxImage">
            <el-input v-model="form.sandboxImage" placeholder="python:3.11-slim" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="8">
              <el-form-item label="超时(秒)" prop="sandboxTimeout">
                <el-input-number v-model="form.sandboxTimeout" :min="1" :max="300" style="width: 100%" controls-position="right" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="内存" prop="sandboxMemory">
                <el-input v-model="form.sandboxMemory" placeholder="128m" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="CPU(核)" prop="sandboxCpus">
                <el-input v-model="form.sandboxCpus" placeholder="0.5" />
              </el-form-item>
            </el-col>
          </el-row>
        </template>
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

<script setup lang="ts" name="AgentTool">
import { addTool, changeToolStatus, delTool, getTool, listTool, updateTool } from '@/api/agent/tool';
import type { ToolForm, ToolQuery, ToolVO } from '@/api/agent/tool/types';
import { listDatasource } from '@/api/agent/datasource';
import type { DatasourceVO } from '@/api/agent/datasource/types';
import ResourceScopeFields from '@/views/agent/components/ResourceScopeFields.vue';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const toolList = ref<ToolVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:tool:edit']);

const queryParam = ref<ToolQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const dialog = ref({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: ToolForm = {
  id: undefined, name: '', description: '', toolType: 'builtin', datasourceId: undefined,
  httpMethod: 'GET', httpUrl: '', httpHeaders: '', httpParams: '',
  sandboxImage: 'python:3.11-slim', sandboxTimeout: 30, sandboxMemory: '128m', sandboxCpus: '0.5',
  remark: ''
};
const form = ref<ToolForm>({ ...initForm });

const rules = {
  name: [{ required: true, message: '请输入工具名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }],
  toolType: [{ required: true, message: '请选择工具类型', trigger: 'change' }],
  datasourceId: [{ required: true, message: '请选择绑定的数据源', trigger: 'change' }],
  httpMethod: [{ required: true, message: '请选择 HTTP 方法', trigger: 'change' }],
  httpUrl: [{ required: true, message: '请输入接口地址', trigger: 'blur' }]
};

const dsOptions = ref<DatasourceVO[]>([]);
const dsName = (id?: number | string) => dsOptions.value.find((d) => d.id == id)?.name || id || '-';
const toolTypeLabel = (t: string) => ({ builtin: '内置', http: 'HTTP', sandbox: '沙箱', sql: '问数' }[t] || t);
const TOOL_TYPE_TAGS: Record<string, 'success' | 'danger' | 'warning' | 'primary' | 'info'> = {
  builtin: 'success', http: 'warning', sandbox: 'danger', sql: 'primary'
};
const toolTypeTagType = (t: string) => TOOL_TYPE_TAGS[t] || 'info';

const loadDatasources = async () => {
  try {
    const res = await listDatasource({ pageNum: 1, pageSize: 100 });
    dsOptions.value = res.data?.rows || [];
  } catch (e) {
    // 无数据源管理权限时静默降级
  }
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listTool(queryParam.value);
    toolList.value = res.data?.rows || [];
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
  dialog.value.title = '注册工具';
  dialog.value.visible = true;
};

const handleEdit = async (row: ToolVO) => {
  isEdit.value = true;
  const res = await getTool(row.id);
  form.value = { ...res.data! };
  dialog.value.title = '编辑工具';
  dialog.value.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return;
    dialog.value.saving = true;
    try {
      if (isEdit.value) {
        await updateTool(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addTool(form.value);
        proxy?.$modal.msgSuccess('注册成功');
      }
      dialog.value.visible = false;
      getList();
    } finally {
      dialog.value.saving = false;
    }
  });
};

const handleStatusChange = async (row: ToolVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  const text = next === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm(`确认要${text}工具「${row.name}」吗?`);
    await changeToolStatus(row.id, next);
    proxy?.$modal.msgSuccess(`${text}成功`);
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: ToolVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除工具「${row.name}」吗?`);
    await delTool(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    getList();
  } catch (e) {
    // 用户取消
  }
};

onMounted(() => {
  getList();
  loadDatasources();
});
</script>