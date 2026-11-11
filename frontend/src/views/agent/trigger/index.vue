<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="触发器名称">
          <el-input v-model="queryParam.keyword" placeholder="请输入名称关键字" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="触发类型">
          <el-select v-model="queryParam.triggerTypeQuery" placeholder="全部" clearable style="width: 140px" @change="handleQuery">
            <el-option label="定时 Cron" value="cron" />
            <el-option label="Webhook" value="webhook" />
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
            <h3>触发器</h3>
            <span class="heading-tip">定时 Cron 按计划自动执行 Agent 对话 / 工作流;Webhook 由外部事件 POST 到 /agent/trigger/webhook/{secret} 拉起执行</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:trigger:add']" type="primary" plain icon="Plus" @click="handleAdd">新建触发器</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="triggerList" class="data-table">
        <el-table-column label="名称" prop="name" min-width="140" show-overflow-tooltip />
        <el-table-column label="触发类型" width="110" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.triggerType === 'cron' ? 'primary' : 'warning'" size="small">
              {{ scope.row.triggerType === 'cron' ? '定时 Cron' : 'Webhook' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="160" show-overflow-tooltip>
          <template #default="scope">
            <span class="target-tag">{{ scope.row.targetType === 'workflow' ? '工作流' : 'Agent' }}</span>
            {{ scope.row.targetName || scope.row.targetId }}
          </template>
        </el-table-column>
        <el-table-column label="计划 / 密钥" min-width="200" show-overflow-tooltip>
          <template #default="scope">
            <code v-if="scope.row.triggerType === 'cron'">{{ scope.row.cronExpr }}</code>
            <template v-else>
              <code>{{ scope.row.secret }}</code>
              <el-button size="small" text type="primary" icon="CopyDocument" @click="copySecret(scope.row as TriggerVO)">复制</el-button>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.enabled === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleEnabledChange(scope.row as TriggerVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近执行" width="170" align="center">
          <template #default="scope">
            <div v-if="scope.row.lastRunTime">
              <el-tag :type="scope.row.lastRunStatus === 'success' ? 'success' : 'danger'" size="small">
                {{ scope.row.lastRunStatus === 'success' ? '成功' : '失败' }}
              </el-tag>
              <div class="run-time">{{ scope.row.lastRunTime }}</div>
            </div>
            <span v-else class="muted">从未执行</span>
          </template>
        </el-table-column>
        <el-table-column label="执行次数" prop="runCount" width="90" align="center" />
        <el-table-column label="操作" width="240" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:trigger:run']" size="small" text type="success" icon="VideoPlay" @click="handleRun(scope.row as TriggerVO)">执行</el-button>
            <el-button v-hasPermi="['agent:trigger:list']" size="small" text type="info" icon="Document" @click="handleLogs(scope.row as TriggerVO)">日志</el-button>
            <el-button v-hasPermi="['agent:trigger:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as TriggerVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:trigger:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as TriggerVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新建/编辑触发器 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符" maxlength="50" />
        </el-form-item>
        <el-form-item label="触发类型" prop="triggerType">
          <el-radio-group v-model="form.triggerType">
            <el-radio value="cron">定时 Cron</el-radio>
            <el-radio value="webhook">Webhook</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标类型" prop="targetType">
          <el-radio-group v-model="form.targetType">
            <el-radio value="agent">Agent 对话</el-radio>
            <el-radio value="workflow">工作流执行</el-radio>
          </el-radio-group>
          <div class="form-tip">工作流执行使用目标 Agent 当前保存的工作流编排</div>
        </el-form-item>
        <el-form-item label="目标 Agent" prop="targetId">
          <el-select v-model="form.targetId" placeholder="选择目标 Agent" filterable style="width: 100%">
            <el-option v-for="a in agentOptions" :key="a.id" :label="a.name" :value="String(a.id)" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.triggerType === 'cron'" label="Cron 表达式" prop="cronExpr">
          <el-input v-model="form.cronExpr" placeholder="如 0 0/5 * * * ? 表示每 5 分钟" maxlength="50" />
          <div class="form-tip">Spring Cron 六位表达式:秒 分 时 日 月 周</div>
        </el-form-item>
        <el-form-item :label="form.targetType === 'workflow' ? '输入模板' : '输入内容'" prop="inputs">
          <el-input
            v-model="form.inputs"
            type="textarea"
            :rows="3"
            maxlength="4000"
            :placeholder="form.targetType === 'workflow' ? 'JSON 对象,例如 topic 字段;Webhook 事件字段将自动合并进来' : 'Agent 对话消息;留空则使用默认提示'"
          />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-value="0" inactive-value="1" />
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

    <!-- 执行日志 -->
    <el-dialog v-model="logDialog.visible" :title="logDialog.title" width="760px" destroy-on-close>
      <el-table v-loading="logDialog.loading" border :data="logDialog.list" max-height="440">
        <el-table-column label="来源" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.invokeSource === 'cron' ? 'primary' : scope.row.invokeSource === 'webhook' ? 'warning' : 'info'">
              {{ scope.row.invokeSource === 'cron' ? '定时' : scope.row.invokeSource === 'webhook' ? 'Webhook' : '手动' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.status === 'success' ? 'success' : 'danger'">
              {{ scope.row.status === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" prop="message" min-width="220" show-overflow-tooltip />
        <el-table-column label="耗时" width="90" align="center">
          <template #default="scope">{{ scope.row.costMs }} ms</template>
        </el-table-column>
        <el-table-column label="执行时间" prop="runTime" width="170" align="center" />
      </el-table>
      <pagination v-model:page="logDialog.query.pageNum" v-model:limit="logDialog.query.pageSize" :total="logDialog.total" @pagination="loadLogs" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentTrigger">
import { addTrigger, changeTriggerEnabled, delTrigger, listTrigger, listTriggerLog, runTrigger, updateTrigger } from '@/api/agent/trigger';
import type { TriggerForm, TriggerLogVO, TriggerQuery, TriggerVO } from '@/api/agent/trigger/types';
import { listAgent } from '@/api/agent/agent';
import type { AgentVO } from '@/api/agent/agent/types';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const triggerList = ref<TriggerVO[]>([]);
const total = ref(0);
const agentOptions = ref<AgentVO[]>([]);
const hasEditPerm = checkPermi(['agent:trigger:edit']);

const queryParam = ref<TriggerQuery>({ pageNum: 1, pageSize: 10, keyword: '', triggerTypeQuery: '' });

const dialog = reactive({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: TriggerForm = { id: undefined, name: '', triggerType: 'cron', targetType: 'agent', targetId: undefined, inputs: '', cronExpr: '', enabled: '0', remark: '' };
const form = ref<TriggerForm>({ ...initForm });

const rules = {
  name: [
    { required: true, message: '请输入触发器名称', trigger: 'blur' },
    { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }
  ],
  triggerType: [{ required: true, message: '请选择触发类型', trigger: 'change' }],
  targetType: [{ required: true, message: '请选择目标类型', trigger: 'change' }],
  targetId: [{ required: true, message: '请选择目标 Agent', trigger: 'change' }],
  cronExpr: [{ required: true, message: '请输入 Cron 表达式', trigger: 'blur' }]
};

const logDialog = reactive({
  visible: false,
  title: '',
  loading: false,
  list: [] as TriggerLogVO[],
  total: 0,
  triggerId: 0 as number | string,
  query: { pageNum: 1, pageSize: 10 }
});

const getList = async () => {
  loading.value = true;
  try {
    const res = await listTrigger(queryParam.value);
    triggerList.value = res.data?.rows || [];
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
  queryParam.value = { pageNum: 1, pageSize: 10, keyword: '', triggerTypeQuery: '' };
  handleQuery();
};

const handleAdd = () => {
  isEdit.value = false;
  form.value = { ...initForm };
  dialog.title = '新建触发器';
  dialog.visible = true;
};

const handleEdit = async (row: TriggerVO) => {
  isEdit.value = true;
  form.value = {
    id: row.id,
    name: row.name,
    triggerType: row.triggerType,
    targetType: row.targetType,
    targetId: String(row.targetId),
    inputs: row.inputs || '',
    cronExpr: row.cronExpr || '',
    enabled: row.enabled,
    remark: row.remark || ''
  };
  dialog.title = '编辑触发器';
  dialog.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.saving = true;
    try {
      if (isEdit.value) {
        await updateTrigger(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addTrigger(form.value);
        proxy?.$modal.msgSuccess('新建成功,Webhook 密钥由服务端自动生成');
      }
      dialog.visible = false;
      getList();
    } finally {
      dialog.saving = false;
    }
  });
};

const handleEnabledChange = async (row: TriggerVO, val: string | number | boolean) => {
  const enabled = val ? '0' : '1';
  await changeTriggerEnabled(row.id, enabled);
  proxy?.$modal.msgSuccess(enabled === '0' ? '已启用' : '已停用');
  getList();
};

const handleRun = async (row: TriggerVO) => {
  try {
    await proxy?.$modal.confirm(`确认立即执行触发器「${row.name}」吗?`);
    const res = await runTrigger(row.id);
    const data = res.data;
    if (data?.status === 'success') {
      proxy?.$modal.msgSuccess(data.message || '执行成功');
    } else {
      proxy?.$modal.msgError(data?.message || '执行失败');
    }
    getList();
  } catch {
    // 用户取消或失败
  }
};

const copySecret = async (row: TriggerVO) => {
  try {
    await navigator.clipboard.writeText(row.secret || '');
    proxy?.$modal.msgSuccess('Webhook 密钥已复制');
  } catch {
    proxy?.$modal.msgWarning('复制失败,请手动选择复制');
  }
};

const handleLogs = (row: TriggerVO) => {
  logDialog.triggerId = row.id;
  logDialog.title = `执行日志 - ${row.name}`;
  logDialog.query.pageNum = 1;
  logDialog.visible = true;
  loadLogs();
};

const loadLogs = async () => {
  logDialog.loading = true;
  try {
    const res = await listTriggerLog(logDialog.triggerId, logDialog.query);
    logDialog.list = res.data?.rows || [];
    logDialog.total = res.data?.total || 0;
  } finally {
    logDialog.loading = false;
  }
};

const handleDelete = async (row: TriggerVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除触发器「${row.name}」吗?其执行日志将一并删除。`);
    await delTrigger(row.id);
    proxy?.$modal.msgSuccess('删除成功');
    getList();
  } catch {
    // 用户取消或失败
  }
};

onMounted(async () => {
  getList();
  const res = await listAgent({ pageNum: 1, pageSize: 200 });
  agentOptions.value = res.data?.rows || [];
});
</script>

<style scoped>
.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.target-tag {
  color: #909399;
  font-size: 12px;
  margin-right: 4px;
}
.run-time {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.muted {
  color: #909399;
}
</style>
