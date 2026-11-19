<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="名称">
          <el-input v-model="queryParam.name" placeholder="客户端名称" clearable style="width: 200px" @keyup.enter="handleQuery" />
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
            <h3>MCP 客户端</h3>
            <span class="heading-tip">外部 Agent(DSH / Claude Code)经 /mcp 访问平台的凭证;每个令牌绑定一个工作空间与授权域,令牌只显示一次</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:mcpclient:add']" type="primary" plain icon="Plus" @click="handleAdd">新增客户端</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="clientList" class="data-table">
        <el-table-column label="名称" prop="name" min-width="130" show-overflow-tooltip />
        <el-table-column label="令牌" width="150" align="center">
          <template #default="scope">
            <span class="mono">{{ scope.row.tokenPrefix }}…</span>
          </template>
        </el-table-column>
        <el-table-column label="可见范围" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.workspaceId ? 'warning' : 'danger'" effect="plain">
              {{ scope.row.workspaceId ? '空间私有' : '平台全量' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="授权域" width="150" align="center">
          <template #default="scope">
            <template v-if="scopesOf(scope.row as McpClientVO).length">
              <el-tag
                v-for="s in scopesOf(scope.row as McpClientVO)"
                :key="s"
                size="small"
                effect="plain"
                style="margin-right: 4px"
              >{{ s === 'knowledge' ? '知识' : '技能' }}</el-tag>
            </template>
            <el-tag v-else size="small" type="info" effect="plain">全部</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.enabled === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as McpClientVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="过期时间" width="165" align="center">
          <template #default="scope">
            <span v-if="scope.row.expireTime">{{ scope.row.expireTime }}</span>
            <span v-else class="muted">长期</span>
          </template>
        </el-table-column>
        <el-table-column label="最近调用" width="165" align="center">
          <template #default="scope">
            <span v-if="scope.row.lastUsedTime">{{ scope.row.lastUsedTime }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:mcpclient:rotate']" size="small" text type="warning" icon="Refresh" @click="handleRotate(scope.row as McpClientVO)">轮转</el-button>
            <el-button v-hasPermi="['agent:mcpclient:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as McpClientVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:mcpclient:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as McpClientVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="客户端名称" prop="name">
          <el-input v-model="form.name" placeholder="如 DSH、Claude Code,仅用于识别" maxlength="64" />
        </el-form-item>
        <el-form-item label="绑定空间">
          <el-select v-model="form.workspaceId" placeholder="留空=平台全量只读(仅超管)" clearable filterable style="width: 100%">
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <div class="form-tip">绑定空间后,该令牌只能看到平台共享 + 本空间的知识库与技能</div>
        </el-form-item>
        <el-form-item label="授权域">
          <el-checkbox-group v-model="scopeList">
            <el-checkbox value="knowledge">知识检索</el-checkbox>
            <el-checkbox value="skill">技能手册</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">全不选=两个域都授权</div>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="留空=长期有效"
            style="width: 100%"
          />
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

    <!-- 令牌只显示一次 -->
    <el-dialog v-model="tokenDialog.visible" title="令牌已生成" width="620px" :close-on-click-modal="false" destroy-on-close>
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px">
        <template #title>
          令牌只显示这一次,关闭后无法再次查看。请立即复制到外部 Agent 的 MCP 配置里;若遗失只能轮转重新生成。
        </template>
      </el-alert>
      <el-form label-width="90px">
        <el-form-item label="客户端">
          <span>{{ tokenInfo.name }}</span>
        </el-form-item>
        <el-form-item label="令牌">
          <el-input :model-value="tokenInfo.token" readonly>
            <template #append>
              <el-button icon="DocumentCopy" @click="copyToken">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="接入地址">
          <el-input :model-value="mcpEndpoint" readonly />
        </el-form-item>
        <el-form-item label="请求头">
          <el-input model-value="X-MCP-Token: <上方的令牌>" readonly />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="tokenDialog.visible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentMcpClient">
import {
  addMcpClient,
  changeMcpClientStatus,
  delMcpClient,
  listMcpClient,
  rotateMcpClientToken,
  updateMcpClient
} from '@/api/agent/mcpclient';
import type { McpClientForm, McpClientQuery, McpClientVO } from '@/api/agent/mcpclient/types';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const clientList = ref<McpClientVO[]>([]);
const total = ref(0);
const workspaceOptions = ref<WorkspaceVO[]>([]);
const hasEditPerm = checkPermi(['agent:mcpclient:edit']);

const queryParam = ref<McpClientQuery>({ pageNum: 1, pageSize: 10, name: '' });

const dialog = ref({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: McpClientForm = { id: undefined, name: '', workspaceId: undefined, scopes: '', expireTime: '', remark: '' };
const form = ref<McpClientForm>({ ...initForm });
/** 授权域用扁平数组,提交时拼成逗号串 */
const scopeList = ref<string[]>([]);

const tokenDialog = ref({ visible: false });
const tokenInfo = ref({ name: '', token: '' });
// 外部 Agent 直连网关地址,不带前端 dev 代理前缀
const mcpEndpoint = computed(() => `${window.location.origin}/mcp`);

const rules = {
  name: [
    { required: true, message: '请输入客户端名称', trigger: 'blur' },
    { max: 64, message: '不能超过 64 字符', trigger: 'blur' }
  ]
};

const scopesOf = (row: McpClientVO) =>
  (row.scopes || '').split(',').map((s) => s.trim()).filter((s) => s !== '');

const getList = async () => {
  loading.value = true;
  try {
    const res = await listMcpClient(queryParam.value);
    clientList.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
};

const loadWorkspaces = async () => {
  if (workspaceOptions.value.length) return;
  try {
    const res = await myWorkspaces();
    workspaceOptions.value = res.data || [];
  } catch {
    workspaceOptions.value = [];
  }
};

const handleQuery = () => {
  queryParam.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParam.value = { pageNum: 1, pageSize: 10, name: '' };
  handleQuery();
};

const handleAdd = () => {
  isEdit.value = false;
  form.value = { ...initForm };
  scopeList.value = [];
  dialog.value.title = '新增 MCP 客户端';
  dialog.value.visible = true;
  loadWorkspaces();
};

const handleEdit = (row: McpClientVO) => {
  isEdit.value = true;
  form.value = {
    id: row.id,
    name: row.name,
    workspaceId: row.workspaceId,
    scopes: row.scopes,
    expireTime: row.expireTime,
    remark: row.remark
  };
  scopeList.value = scopesOf(row);
  dialog.value.title = '编辑 MCP 客户端(令牌不变)';
  dialog.value.visible = true;
  loadWorkspaces();
};

const submitForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.value.saving = true;
    try {
      const payload: McpClientForm = { ...form.value, scopes: scopeList.value.join(',') };
      if (isEdit.value) {
        await updateMcpClient(payload);
        proxy?.$modal.msgSuccess('修改成功');
        dialog.value.visible = false;
      } else {
        const res = await addMcpClient(payload);
        dialog.value.visible = false;
        showToken(res.data);
      }
      getList();
    } finally {
      dialog.value.saving = false;
    }
  });
};

const showToken = (data?: { name: string; token: string }) => {
  if (!data?.token) return;
  tokenInfo.value = { name: data.name, token: data.token };
  tokenDialog.value.visible = true;
};

const copyToken = async () => {
  try {
    await navigator.clipboard.writeText(tokenInfo.value.token);
    proxy?.$modal.msgSuccess('已复制');
  } catch {
    proxy?.$modal.msgError('复制失败,请手动选中复制');
  }
};

const handleRotate = async (row: McpClientVO) => {
  try {
    await proxy?.$modal.confirm(
      `轮转后「${row.name}」的旧令牌立即失效,外部 Agent 需同步更新配置。确认轮转吗?`
    );
    const res = await rotateMcpClientToken(row.id);
    showToken(res.data);
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleStatusChange = async (row: McpClientVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  try {
    await proxy?.$modal.confirm(
      next === '1'
        ? `确认停用「${row.name}」吗?该令牌将在 30 秒内失效。`
        : `确认启用「${row.name}」吗?该令牌将在 30 秒内恢复可用。`
    );
    await changeMcpClientStatus(row.id, next);
    proxy?.$modal.msgSuccess(next === '0' ? '启用成功' : '停用成功');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: McpClientVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除「${row.name}」吗?该令牌将立即失效且不可恢复。`);
    await delMcpClient(row.id);
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
.table-heading .heading-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.form-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
