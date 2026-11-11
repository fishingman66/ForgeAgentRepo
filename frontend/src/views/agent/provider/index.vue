<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="编码/名称" clearable style="width: 200px" @keyup.enter="handleQuery" />
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
            <h3>供应商配置</h3>
            <span class="heading-tip">停用供应商将立即拦截其下模型的新会话发起,不影响进行中的会话</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:provider:add']" type="primary" plain icon="Plus" @click="handleAdd">新增供应商</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="providerList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="供应商编码" prop="providerCode" min-width="140" show-overflow-tooltip />
        <el-table-column label="供应商名称" prop="providerName" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as ProviderVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="140" show-overflow-tooltip />
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:provider:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as ProviderVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:provider:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as ProviderVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新增/编辑供应商 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="供应商编码" prop="providerCode">
          <el-input v-model="form.providerCode" :disabled="isEdit" placeholder="小写字母开头,如 openai" maxlength="30" />
          <div v-if="isEdit" class="form-tip">编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="供应商名称" prop="providerName">
          <el-input v-model="form.providerName" maxlength="50" />
        </el-form-item>
        <el-form-item label="备注">
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

<script setup lang="ts" name="AgentProvider">
import { addProvider, changeProviderStatus, delProvider, listProvider, updateProvider } from '@/api/agent/provider';
import type { ProviderForm, ProviderQuery, ProviderVO } from '@/api/agent/provider/types';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const providerList = ref<ProviderVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:provider:edit']);

const queryParam = ref<ProviderQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const dialog = reactive({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: ProviderForm = { id: undefined, providerCode: '', providerName: '', remark: '' };
const form = ref<ProviderForm>({ ...initForm });

const rules = {
  providerCode: [
    { required: true, message: '请输入供应商编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{1,29}$/, message: '小写字母开头,仅含小写字母/数字/下划线,2-30 字符', trigger: 'blur' }
  ],
  providerName: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }]
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listProvider(queryParam.value);
    providerList.value = res.data?.rows || [];
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
  dialog.title = '新增供应商';
  dialog.visible = true;
};

const handleEdit = (row: ProviderVO) => {
  isEdit.value = true;
  form.value = { ...row };
  dialog.title = '编辑供应商';
  dialog.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.saving = true;
    try {
      if (isEdit.value) {
        await updateProvider(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addProvider(form.value);
        proxy?.$modal.msgSuccess('新增成功');
      }
      dialog.visible = false;
      getList();
    } finally {
      dialog.saving = false;
    }
  });
};

const handleStatusChange = async (row: ProviderVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  const text = next === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm(
      next === '1'
        ? `确认停用供应商「${row.providerName}」吗?其下模型的所有新会话将被立即拦截。`
        : `确认启用供应商「${row.providerName}」吗?`
    );
    await changeProviderStatus(row.id, next);
    proxy?.$modal.msgSuccess(`${text}成功`);
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: ProviderVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除供应商「${row.providerName}」吗?被模型引用的供应商不可删除。`);
    await delProvider(row.id);
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
