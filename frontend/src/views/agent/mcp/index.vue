<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="名称/描述" clearable style="width: 200px" @keyup.enter="handleQuery" />
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
            <h3>MCP 服务器</h3>
            <span class="heading-tip">注册外部 MCP 服务器供 Agent 绑定;审批模式决定其工具调用是否需要人工确认</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:mcp:add']" type="primary" plain icon="Plus" @click="handleAdd">新增服务器</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="mcpList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="名称" prop="name" min-width="140" show-overflow-tooltip />
        <el-table-column label="传输类型" width="130" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.serverType === 'sse' ? 'warning' : 'success'" effect="plain">
              {{ scope.row.serverType === 'sse' ? 'SSE' : 'Streamable HTTP' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务地址" prop="url" min-width="200" show-overflow-tooltip />
        <el-table-column label="审批模式" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="approvalModeType(scope.row.approvalMode)" effect="light">
              {{ approvalModeLabel(scope.row.approvalMode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工具数" width="80" align="center">
          <template #default="scope">
            <span v-if="scope.row.toolCount != null">{{ scope.row.toolCount }}</span>
            <span v-else style="color: var(--el-text-color-secondary)">未测</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as McpServerVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="210" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:mcp:test']" size="small" text type="success" icon="Connection" :loading="testingId === scope.row.id" @click="handleTest(scope.row as McpServerVO)">测试连接</el-button>
            <el-button v-hasPermi="['agent:mcp:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as McpServerVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:mcp:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as McpServerVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新增/编辑 MCP 服务器 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="600px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="服务器名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,全局唯一" maxlength="50" />
        </el-form-item>
        <el-form-item label="传输类型" prop="serverType">
          <el-select v-model="form.serverType" style="width: 100%">
            <el-option label="Streamable HTTP" value="streamable_http" />
            <el-option label="SSE" value="sse" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务地址" prop="url">
          <el-input v-model="form.url" placeholder="如 http://127.0.0.1:3001/mcp" maxlength="300" />
        </el-form-item>
        <el-form-item label="自定义请求头" prop="headers">
          <el-input v-model="form.headers" type="textarea" :rows="3" placeholder='可选,JSON 格式,如 {"Authorization":"Bearer xxx"}' maxlength="5000" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="请求超时(秒)" prop="timeoutSeconds">
              <el-input-number v-model="form.timeoutSeconds" :min="3" :max="300" :step="5" style="width: 100%" controls-position="right" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="审批模式" prop="approvalMode">
          <el-radio-group v-model="form.approvalMode">
            <el-radio value="auto">auto 自动放行</el-radio>
            <el-radio value="ask">ask 人工审批</el-radio>
            <el-radio value="deny">deny 全部拒绝</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.approvalMode === 'ask'" label="审批超时(秒)" prop="approvalTimeout">
          <el-input-number v-model="form.approvalTimeout" :min="30" :max="3600" :step="30" style="width: 100%" controls-position="right" />
          <div class="form-tip">等待人工审批超过该时长后,本次工具调用按拒绝处理</div>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentMcp">
import { addMcpServer, changeMcpServerStatus, delMcpServer, listMcpServer, testMcpServer, updateMcpServer } from '@/api/agent/mcp';
import type { McpServerForm, McpServerQuery, McpServerVO } from '@/api/agent/mcp/types';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const mcpList = ref<McpServerVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:mcp:edit']);
const testingId = ref<string | number>('');

const queryParam = ref<McpServerQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const approvalModeMap: Record<string, { label: string; type: 'success' | 'warning' | 'danger' }> = {
  auto: { label: 'auto 自动', type: 'success' },
  ask: { label: 'ask 审批', type: 'warning' },
  deny: { label: 'deny 拒绝', type: 'danger' }
};
const approvalModeLabel = (m?: string) => approvalModeMap[m || 'auto']?.label || m || '-';
const approvalModeType = (m?: string): 'success' | 'info' | 'warning' | 'danger' => approvalModeMap[m || 'auto']?.type || 'info';

const dialog = ref({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: McpServerForm = {
  id: undefined, name: '', serverType: 'streamable_http', url: '', headers: '',
  timeoutSeconds: 30, approvalMode: 'auto', approvalTimeout: 300, description: ''
};
const form = ref<McpServerForm>({ ...initForm });

const rules = {
  name: [{ required: true, message: '请输入服务器名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }],
  serverType: [{ required: true, message: '请选择传输类型', trigger: 'change' }],
  url: [
    { required: true, message: '请输入服务地址', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: '地址须以 http:// 或 https:// 开头', trigger: 'blur' }
  ],
  headers: [
    {
      validator: (_rule: any, value: string, callback: (err?: Error) => void) => {
        if (!value) return callback();
        try {
          const parsed = JSON.parse(value);
          if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return callback();
          return callback(new Error('须为 JSON 对象'));
        } catch {
          return callback(new Error('须为合法 JSON'));
        }
      },
      trigger: 'blur'
    }
  ]
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listMcpServer(queryParam.value);
    mcpList.value = res.data?.rows || [];
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
  dialog.value.title = '新增 MCP 服务器';
  dialog.value.visible = true;
};

const handleEdit = (row: McpServerVO) => {
  isEdit.value = true;
  form.value = { ...row };
  dialog.value.title = '编辑 MCP 服务器';
  dialog.value.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.value.saving = true;
    try {
      if (isEdit.value) {
        await updateMcpServer(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addMcpServer(form.value);
        proxy?.$modal.msgSuccess('新增成功');
      }
      dialog.value.visible = false;
      getList();
    } finally {
      dialog.value.saving = false;
    }
  });
};

const handleTest = async (row: McpServerVO) => {
  testingId.value = row.id;
  try {
    const res = await testMcpServer(row.id);
    const tools = res.data || [];
    if (tools.length === 0) {
      proxy?.$modal.msgWarning('连接成功,但未发现任何工具');
    } else {
      await ElMessageBox.alert(
        tools.map((t, i) => `${i + 1}. ${t}`).join('\n'),
        `「${row.name}」连接成功,发现 ${tools.length} 个工具`,
        { confirmButtonText: '知道了', customStyle: { whiteSpace: 'pre-line' } as any }
      );
    }
    getList();
  } finally {
    testingId.value = '';
  }
};

const handleStatusChange = async (row: McpServerVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  const text = next === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm(
      next === '1'
        ? `确认启用 MCP 服务器「${row.name}」吗?绑定它的 Agent 将可调用其工具。`
        : `确认停用 MCP 服务器「${row.name}」吗?绑定它的 Agent 将无法再调用其工具。`
    );
    await changeMcpServerStatus(row.id, next);
    proxy?.$modal.msgSuccess(`${text}成功`);
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: McpServerVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除 MCP 服务器「${row.name}」吗?正在绑定该服务器的 Agent 不可删除。`);
    await delMcpServer(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    getList();
  } catch (e) {
    // 用户取消或失败
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
