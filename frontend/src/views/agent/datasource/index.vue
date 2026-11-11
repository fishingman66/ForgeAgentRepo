<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="名称" clearable style="width: 200px" @keyup.enter="handleQuery" />
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
            <h3>问数数据源</h3>
            <span class="heading-tip">注册业务库供智能问数查询;强烈建议使用只读账号并接入分析库/从库</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:datasource:add']" type="primary" plain icon="Plus" @click="handleAdd">新增数据源</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="dsList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="范围" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.workspaceId ? 'warning' : 'success'" effect="plain">
              {{ scope.row.workspaceId ? '空间私有' : '平台共享' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" prop="name" min-width="130" show-overflow-tooltip />
        <el-table-column label="连接" min-width="220" show-overflow-tooltip>
          <template #default="scope">
            <span class="mono">{{ scope.row.host }}:{{ scope.row.port }}/{{ scope.row.dbName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="账号" prop="username" width="110" show-overflow-tooltip />
        <el-table-column label="行数上限" width="90" align="center">
          <template #default="scope">{{ scope.row.maxRows ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="超时(秒)" width="90" align="center">
          <template #default="scope">{{ scope.row.timeoutSeconds ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="表结构" width="140" align="center">
          <template #default="scope">
            <span v-if="scope.row.schemaSize > 0">
              {{ schemaSyncedText(scope.row as DatasourceVO) }}
            </span>
            <span v-else style="color: var(--el-text-color-secondary)">未同步</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as any, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="400" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:datasource:test']" size="small" text type="success" icon="Connection" :loading="testingId === scope.row.id" @click="handleTest(scope.row as DatasourceVO)">测试连接</el-button>
            <el-button v-hasPermi="['agent:datasource:test']" size="small" text type="warning" icon="Refresh" :loading="syncingId === scope.row.id" @click="handleSyncSchema(scope.row as DatasourceVO)">同步结构</el-button>
            <el-button v-hasPermi="['agent:datasource:query']" size="small" text type="info" icon="View" @click="handleViewSchema(scope.row as DatasourceVO)">查看</el-button>
            <el-button v-hasPermi="['agent:datasource:query']" size="small" text type="primary" icon="Collection" @click="handleExamples(scope.row as DatasourceVO)">示例</el-button>
            <el-button v-hasPermi="['agent:datasource:query']" size="small" text type="primary" icon="Histogram" @click="handleSemantic(scope.row as DatasourceVO)">语义层</el-button>
            <el-button v-hasPermi="['agent:datasource:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as DatasourceVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:datasource:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as DatasourceVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新增/编辑数据源 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <ResourceScopeFields v-model="form" />
        <el-form-item label="数据源名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,全局唯一" maxlength="50" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="16">
            <el-form-item label="数据库类型" prop="dbType">
              <el-select v-model="form.dbType" style="width: 100%" disabled>
                <el-option label="MySQL" value="mysql" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="端口" prop="port" label-width="60px">
              <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="主机地址" prop="host">
          <el-input v-model="form.host" placeholder="如 127.0.0.1" maxlength="200" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="数据库名" prop="dbName">
              <el-input v-model="form.dbName" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" maxlength="100" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="连接密码" prop="password">
          <el-input v-model="form.password" type="password" show-password :placeholder="isEdit ? '留空表示不修改' : '只读账号密码'" maxlength="200" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="额外JDBC参数" prop="params">
          <el-input v-model="form.params" placeholder="可选,如 useSSL=false&serverTimezone=Asia/Shanghai" maxlength="500" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="行数上限" prop="maxRows">
              <el-input-number v-model="form.maxRows" :min="1" :max="2000" :step="50" controls-position="right" style="width: 100%" />
              <div class="form-tip">单次查询最多返回行数,超出自动截断</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="查询超时(秒)" prop="timeoutSeconds">
              <el-input-number v-model="form.timeoutSeconds" :min="1" :max="120" :step="5" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="描述该库的业务含义,会注入问数提示词帮助模型理解" />
        </el-form-item>
        <el-form-item label="可查询表" prop="tableWhitelist">
          <el-select
            v-model="whitelistArray"
            multiple
            filterable
            allow-create
            default-first-option
            :loading="whitelistLoading"
            style="width: 100%"
            placeholder="不填表示可查询全部表;建议仅开放分析所需表"
          >
            <el-option v-for="t in tableOptions" :key="t.name" :label="t.comment ? `${t.name} (${t.comment})` : t.name" :value="t.name" />
          </el-select>
          <div class="form-tip">不填表示可查询全部表;建议仅开放分析所需表,表清单来自最近一次「同步结构」</div>
        </el-form-item>
        <el-form-item label="语义层注入">
          <el-switch v-model="form.semanticEnabled" active-value="1" inactive-value="0" />
          <span class="form-tip">启用后,问数时按用户问题动态注入匹配的指标/维度/取值,提升精准度</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="dialog.testing" @click="handleTestInForm">测试连接</el-button>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 表结构快照查看 -->
    <el-dialog v-model="schemaDialog.visible" :title="`表结构快照 - ${schemaDialog.name}`" width="760px" destroy-on-close>
      <div v-loading="schemaDialog.loading">
        <pre v-if="schemaDialog.content" class="schema-pre">{{ schemaDialog.content }}</pre>
        <el-empty v-else-if="!schemaDialog.loading" description="尚未同步表结构" />
      </div>
    </el-dialog>

    <!-- 问数示例管理 -->
    <el-drawer v-model="exampleDrawer.visible" :title="`问数示例 - ${exampleDrawer.name}`" size="720px" destroy-on-close>
      <div class="example-toolbar">
        <span class="heading-tip">示例(问题→SQL)会注入问数提示词,帮助模型学习该库的查询写法</span>
        <el-button v-hasPermi="['agent:datasource:edit']" type="primary" plain size="small" icon="Plus" @click="handleExampleAdd">新增示例</el-button>
      </div>
      <el-table v-loading="exampleDrawer.loading" border :data="exampleDrawer.list">
        <el-table-column label="问题" prop="question" min-width="200" show-overflow-tooltip />
        <el-table-column label="SQL" min-width="240" show-overflow-tooltip>
          <template #default="scope"><span class="mono">{{ scope.row.sqlContent }}</span></template>
        </el-table-column>
        <el-table-column label="排序" prop="sort" width="70" align="center" />
        <el-table-column label="操作" width="120" align="center">
          <template #default="scope">
            <el-button v-hasPermi="['agent:datasource:edit']" size="small" text type="primary" icon="Edit" @click="handleExampleEdit(scope.row as DatasourceExampleVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:datasource:edit']" size="small" text type="danger" icon="Delete" @click="handleExampleDelete(scope.row as DatasourceExampleVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!exampleDrawer.loading && exampleDrawer.list.length === 0" description="暂无示例,新增后可提升问数准确率" />
    </el-drawer>

    <!-- 语义层管理 -->
    <SemanticDrawer
      v-model:visible="semanticDrawer.visible"
      :datasource-id="semanticDrawer.dsId"
      :datasource-name="semanticDrawer.name"
    />

    <!-- 示例编辑 -->
    <el-dialog v-model="exampleDialog.visible" :title="exampleDialog.title" width="600px" append-to-body destroy-on-close>
      <el-form ref="exampleFormRef" :model="exampleDialog.form" :rules="exampleRules" label-width="90px">
        <el-form-item label="示例问题" prop="question">
          <el-input v-model="exampleDialog.form.question" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="如: 上月各产品的销售额排行" />
        </el-form-item>
        <el-form-item label="示例SQL" prop="sqlContent">
          <el-input v-model="exampleDialog.form.sqlContent" type="textarea" :rows="5" placeholder="该问题对应的正确 SELECT 语句" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="exampleDialog.form.sort" :min="0" :max="999" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exampleDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="exampleDialog.saving" @click="submitExample">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentDatasource">
import {
  addDatasource,
  addDatasourceExample,
  changeDatasourceStatus,
  delDatasource,
  delDatasourceExample,
  getDatasourceSchema,
  listDatasource,
  listDatasourceExamples,
  listDatasourceTables,
  refreshDatasourceSchema,
  testDatasource,
  updateDatasource,
  updateDatasourceExample
} from '@/api/agent/datasource';
import type { DatasourceExampleForm, DatasourceExampleVO, DatasourceForm, DatasourceQuery, DatasourceTableItem, DatasourceVO } from '@/api/agent/datasource/types';
import ResourceScopeFields from '@/views/agent/components/ResourceScopeFields.vue';
import { checkPermi } from '@/utils/permission';
import { parseTime } from '@/utils/forge';
import SemanticDrawer from './SemanticDrawer.vue';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const dsList = ref<DatasourceVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:datasource:edit']);
const testingId = ref<string | number>('');
const syncingId = ref<string | number>('');

const queryParam = ref<DatasourceQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const dialog = reactive({ visible: false, title: '', saving: false, testing: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: DatasourceForm = {
  id: undefined, name: '', dbType: 'mysql', host: '', port: 3306, dbName: '',
  username: '', password: '', params: '', maxRows: 500, timeoutSeconds: 15, description: '',
  semanticEnabled: '0'
};
const form = ref<DatasourceForm>({ ...initForm });

const schemaDialog = reactive({ visible: false, name: '', content: '', loading: false });

/** 白名单:表单内以数组交互,提交时转逗号字符串 */
const whitelistArray = ref<string[]>([]);
const tableOptions = ref<DatasourceTableItem[]>([]);
const whitelistLoading = ref(false);

/** 问数示例管理 */
const exampleDrawer = reactive({ visible: false, dsId: '', name: '', list: [] as DatasourceExampleVO[], loading: false });
const semanticDrawer = reactive({ visible: false, dsId: '', name: '' });
const exampleFormRef = ref();
const exampleDialog = reactive({
  visible: false,
  title: '',
  saving: false,
  form: { datasourceId: '', question: '', sqlContent: '', sort: 0 } as DatasourceExampleForm
});
const exampleRules = {
  question: [{ required: true, message: '请输入示例问题', trigger: 'blur' }],
  sqlContent: [{ required: true, message: '请输入示例SQL', trigger: 'blur' }]
};

const rules = {
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  dbName: [{ required: true, message: '请输入数据库名', trigger: 'blur' }],
  username: [{ required: true, message: '请输入连接用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入连接密码', trigger: 'blur' }]
};

const schemaSyncedText = (row: DatasourceVO) => {
  const time = row.schemaSyncedAt ? parseTime(row.schemaSyncedAt, '{m}-{d} {h}:{i}') : '';
  return `${time} · ${(row.schemaSize / 1000).toFixed(1)}K字`;
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listDatasource(queryParam.value);
    dsList.value = res.data?.rows || [];
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
  whitelistArray.value = [];
  tableOptions.value = [];
  dialog.title = '新增问数数据源';
  dialog.visible = true;
};

const handleEdit = (row: DatasourceVO) => {
  isEdit.value = true;
  form.value = { ...row, password: '' };
  whitelistArray.value = row.tableWhitelist ? row.tableWhitelist.split(',').filter(Boolean) : [];
  dialog.title = '编辑问数数据源';
  dialog.visible = true;
  loadTableOptions(row.id);
};

/** 加载已同步的表清单作为白名单选项 */
const loadTableOptions = async (id: string | number) => {
  whitelistLoading.value = true;
  try {
    const res = await listDatasourceTables(id);
    tableOptions.value = res.data || [];
  } finally {
    whitelistLoading.value = false;
  }
};

const doTest = async () => {
  const res = await testDatasource(form.value);
  proxy?.$modal.msgSuccess(`连接成功,耗时 ${res.data?.elapsedMs ?? '-'} ms`);
};

const handleTestInForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.testing = true;
    try {
      await doTest();
    } finally {
      dialog.testing = false;
    }
  });
};

const submitForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.saving = true;
    try {
      form.value.tableWhitelist = whitelistArray.value.join(',');
      if (isEdit.value) {
        await updateDatasource(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addDatasource(form.value);
        proxy?.$modal.msgSuccess('新增成功,建议立即「同步结构」生成表结构快照');
      }
      dialog.visible = false;
      getList();
    } finally {
      dialog.saving = false;
    }
  });
};

const handleTest = async (row: DatasourceVO) => {
  testingId.value = row.id;
  try {
    const res = await testDatasource({ ...row } as DatasourceForm);
    proxy?.$modal.msgSuccess(`连接成功,耗时 ${res.data?.elapsedMs ?? '-'} ms`);
  } finally {
    testingId.value = '';
  }
};

const handleSyncSchema = async (row: DatasourceVO) => {
  syncingId.value = row.id;
  try {
    const res = await refreshDatasourceSchema(row.id);
    const tables = (res.data || '').split('\n').filter((l) => l.startsWith('表 ')).length;
    proxy?.$modal.msgSuccess(`表结构同步成功,共 ${tables} 张表`);
    getList();
  } finally {
    syncingId.value = '';
  }
};

const handleViewSchema = async (row: DatasourceVO) => {
  schemaDialog.name = row.name;
  schemaDialog.content = '';
  schemaDialog.visible = true;
  schemaDialog.loading = true;
  try {
    const res = await getDatasourceSchema(row.id);
    schemaDialog.content = res.data || '';
  } finally {
    schemaDialog.loading = false;
  }
};

const handleStatusChange = async (row: DatasourceVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  const text = next === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm(
      next === '0'
        ? `确认启用数据源「${row.name}」吗?绑定它的问数工具将恢复可用。`
        : `确认停用数据源「${row.name}」吗?绑定它的问数工具将无法再执行查询。`
    );
    await changeDatasourceStatus(row.id, next);
    proxy?.$modal.msgSuccess(`${text}成功`);
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: DatasourceVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除数据源「${row.name}」吗?绑定该数据源的问数工具将失效。`);
    await delDatasource(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

/** 打开示例管理抽屉 */
const handleExamples = async (row: DatasourceVO) => {
  exampleDrawer.dsId = row.id;
  exampleDrawer.name = row.name;
  exampleDrawer.visible = true;
  await loadExamples();
};

const handleSemantic = (row: DatasourceVO) => {
  semanticDrawer.dsId = row.id;
  semanticDrawer.name = row.name;
  semanticDrawer.visible = true;
};

const loadExamples = async () => {
  exampleDrawer.loading = true;
  try {
    const res = await listDatasourceExamples(exampleDrawer.dsId);
    exampleDrawer.list = res.data || [];
  } finally {
    exampleDrawer.loading = false;
  }
};

const handleExampleAdd = () => {
  exampleDialog.form = { datasourceId: exampleDrawer.dsId, question: '', sqlContent: '', sort: 0 };
  exampleDialog.title = '新增示例';
  exampleDialog.visible = true;
};

const handleExampleEdit = (row: DatasourceExampleVO) => {
  exampleDialog.form = { ...row };
  exampleDialog.title = '编辑示例';
  exampleDialog.visible = true;
};

const submitExample = () => {
  exampleFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    exampleDialog.saving = true;
    try {
      if (exampleDialog.form.id) {
        await updateDatasourceExample(exampleDialog.form);
      } else {
        await addDatasourceExample(exampleDialog.form);
      }
      proxy?.$modal.msgSuccess('保存成功');
      exampleDialog.visible = false;
      loadExamples();
    } finally {
      exampleDialog.saving = false;
    }
  });
};

const handleExampleDelete = async (row: DatasourceExampleVO) => {
  try {
    await proxy?.$modal.confirm('确认删除该示例吗?');
    await delDatasourceExample(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    loadExamples();
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
.mono {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}
.example-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.schema-pre {
  max-height: 60vh;
  overflow: auto;
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>