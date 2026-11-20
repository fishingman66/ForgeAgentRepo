<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="名称/摘要" clearable style="width: 200px" @keyup.enter="handleQuery" />
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
            <h3>技能管理</h3>
            <span class="heading-tip">声明式技能包:操作手册 + 工具/知识库引用;Agent 只加载一行摘要目录,模型经 load_skill 按需取全文</span>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Shop" @click="openMarket">技能市场</el-button>
            <el-button v-hasPermi="['agent:skill:add']" type="primary" plain icon="Plus" @click="handleAdd">新增技能</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="skillList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="名称" prop="name" min-width="130" show-overflow-tooltip />
        <el-table-column label="归属" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.workspaceId ? 'warning' : 'success'" effect="plain">
              {{ scope.row.workspaceId ? '空间私有' : '平台共享' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="一行摘要" prop="description" min-width="220" show-overflow-tooltip />
        <el-table-column label="版本" prop="version" width="70" align="center">
          <template #default="scope">
            <span>v{{ scope.row.version ?? 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="引用" width="160" align="center">
          <template #default="scope">
            <el-tag v-if="countRefs(scope.row as SkillVO).tools" size="small" effect="plain" style="margin-right: 4px">工具 {{ countRefs(scope.row as SkillVO).tools }}</el-tag>
            <el-tag v-if="countRefs(scope.row as SkillVO).mcp" size="small" type="warning" effect="plain" style="margin-right: 4px">MCP {{ countRefs(scope.row as SkillVO).mcp }}</el-tag>
            <el-tag v-if="countRefs(scope.row as SkillVO).kb" size="small" type="info" effect="plain">知识 {{ countRefs(scope.row as SkillVO).kb }}</el-tag>
            <span v-if="!countRefs(scope.row as SkillVO).tools && !countRefs(scope.row as SkillVO).mcp && !countRefs(scope.row as SkillVO).kb" style="color: var(--el-text-color-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.status === '0'"
              :disabled="!hasEditPerm"
              @change="(val: string | number | boolean) => handleStatusChange(scope.row as SkillVO, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['agent:skill:install']" size="small" text type="success" icon="Download" @click="openInstall(scope.row as SkillVO)">安装管理</el-button>
            <el-button v-hasPermi="['agent:skill:edit']" size="small" text type="primary" icon="Edit" @click="handleEdit(scope.row as SkillVO)">编辑</el-button>
            <el-button v-hasPermi="['agent:skill:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as SkillVO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 新增/编辑技能 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="760px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <ResourceScopeFields v-model="form" />
        <el-form-item label="技能名称" prop="name">
          <el-input v-model="form.name" placeholder="2-50 字符,同一空间内唯一;模型按名称原文加载" maxlength="50" />
        </el-form-item>
        <el-form-item label="一行摘要" prop="description">
          <el-input v-model="form.description" placeholder="进 system prompt 的目录行,写清适用场景,不超过 100 字" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="操作手册" prop="instructions">
          <el-input
            v-model="form.instructions"
            type="textarea"
            :rows="10"
            placeholder="Markdown 操作手册,只在模型调用 load_skill 时才进入上下文。写清操作步骤、可使用的工具与注意事项"
            maxlength="20000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="引用工具ID" prop="toolIds">
          <el-input v-model="form.toolIds" placeholder="逗号分隔的平台工具 ID,安装后并入应用授权边界" maxlength="500" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="引用MCP ID" prop="mcpIds">
              <el-input v-model="form.mcpIds" placeholder="逗号分隔的 MCP 服务器 ID" maxlength="500" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="引用知识库ID" prop="knowledgeIds">
              <el-input v-model="form.knowledgeIds" placeholder="逗号分隔的知识库 ID(手册中引导使用)" maxlength="500" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="提示词片段" prop="promptFragment">
          <el-input v-model="form.promptFragment" type="textarea" :rows="3" placeholder="加载手册后追加的约束,如语气要求、红线说明(不超过 2000 字)" maxlength="2000" show-word-limit />
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

    <!-- 安装管理:一次列出全部已发布应用,直接在行内切换安装/卸载 -->
    <el-dialog v-model="installDialog.visible" :title="'安装管理 · ' + (installSkillRow?.name ?? '')" width="720px" destroy-on-close>
      <el-alert v-if="installSkillRow" type="info" :closable="false" style="margin-bottom: 12px">
        <template #title>
          安装后目标应用可获得该技能的手册与引用工具;卸载后目录与 load_skill 立即失效。操作即时生效,无需重启应用。
        </template>
      </el-alert>
      <div class="install-toolbar">
        <el-input v-model="installKeyword" placeholder="搜索应用名称" clearable style="width: 220px" />
        <span class="install-tip">已安装 {{ installedCount }} / 共 {{ installTargets.length }} 个已发布应用</span>
      </div>
      <el-table :data="filteredTargets" size="small" border max-height="380" v-loading="installLoading" empty-text="暂无已发布应用">
        <el-table-column label="应用" prop="agentName" min-width="160" show-overflow-tooltip />
        <el-table-column label="归属" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.workspaceId ? 'warning' : 'success'" effect="plain">
              {{ scope.row.workspaceId ? '空间私有' : '平台共享' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="安装时间" width="170" align="center">
          <template #default="scope">
            <span v-if="scope.row.installTime">{{ scope.row.installTime }}</span>
            <span v-else style="color: var(--el-text-color-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="scope">
            <el-button
              size="small"
              text
              :type="scope.row.installed ? 'danger' : 'success'"
              :loading="installBusy === scope.row.agentId"
              @click="toggleInstall(scope.row as SkillInstallTargetVO)"
            >
              {{ scope.row.installed ? '卸载' : '安装' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="installDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>
    <SkillMarketDrawer ref="marketRef" />
  </div>
</template>

<script setup lang="ts" name="AgentSkill">
import { addSkill, changeSkillStatus, delSkill, installSkill, listInstallTargets, listSkill, uninstallSkill, updateSkill } from '@/api/agent/skill';
import type { SkillForm, SkillInstallTargetVO, SkillQuery, SkillVO } from '@/api/agent/skill/types';
import ResourceScopeFields from '@/views/agent/components/ResourceScopeFields.vue';
import SkillMarketDrawer from './components/SkillMarketDrawer.vue';
import { checkPermi } from '@/utils/permission';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const skillList = ref<SkillVO[]>([]);
const total = ref(0);
const hasEditPerm = checkPermi(['agent:skill:edit']);

// C5:技能市场抽屉(只读发现平台共享技能 + 一键绑定到 Agent)
const marketRef = ref<InstanceType<typeof SkillMarketDrawer>>();
const openMarket = () => marketRef.value?.open();

const queryParam = ref<SkillQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const dialog = ref({ visible: false, title: '', saving: false });
const isEdit = ref(false);
const formRef = ref();
const initForm: SkillForm = {
  id: undefined, workspaceId: undefined, shared: false,
  name: '', description: '', instructions: '',
  toolIds: '', mcpIds: '', knowledgeIds: '', promptFragment: '', remark: ''
};
const form = ref<SkillForm>({ ...initForm });

const installDialog = ref({ visible: false });
const installSkillRow = ref<SkillVO | null>(null);
const installTargets = ref<SkillInstallTargetVO[]>([]);
const installKeyword = ref('');
const installLoading = ref(false);
const installBusy = ref<string>('');

/** 已安装计数与搜索过滤都基于同一份 targets,避免两次请求口径不一致 */
const installedCount = computed(() => installTargets.value.filter((t) => t.installed).length);
const filteredTargets = computed(() => {
  const kw = installKeyword.value.trim().toLowerCase();
  if (!kw) return installTargets.value;
  return installTargets.value.filter((t) => (t.agentName || '').toLowerCase().includes(kw));
});

const rules = {
  name: [{ required: true, message: '请输入技能名称', trigger: 'blur' }, { min: 2, max: 50, message: '长度须在 2-50 字符', trigger: 'blur' }],
  description: [
    { required: true, message: '请输入一行摘要', trigger: 'blur' },
    { max: 100, message: '摘要不能超过 100 字符', trigger: 'blur' }
  ]
};

const countRefs = (row: SkillVO) => {
  const count = (s?: string) => (s ? s.split(',').filter((x) => x.trim() !== '').length : 0);
  return { tools: count(row.toolIds), mcp: count(row.mcpIds), kb: count(row.knowledgeIds) };
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listSkill(queryParam.value);
    skillList.value = res.data?.rows || [];
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
  dialog.value.title = '新增技能';
  dialog.value.visible = true;
};

const handleEdit = (row: SkillVO) => {
  isEdit.value = true;
  form.value = { ...row };
  dialog.value.title = '编辑技能(保存后版本自动 +1)';
  dialog.value.visible = true;
};

const submitForm = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    dialog.value.saving = true;
    try {
      if (isEdit.value) {
        await updateSkill(form.value);
        proxy?.$modal.msgSuccess('修改成功');
      } else {
        await addSkill(form.value);
        proxy?.$modal.msgSuccess('新增成功');
      }
      dialog.value.visible = false;
      getList();
    } finally {
      dialog.value.saving = false;
    }
  });
};

const openInstall = async (row: SkillVO) => {
  installSkillRow.value = row;
  installKeyword.value = '';
  installDialog.value.visible = true;
  await loadInstallTargets();
};

const loadInstallTargets = async () => {
  const row = installSkillRow.value;
  if (!row) return;
  installLoading.value = true;
  try {
    const res = await listInstallTargets(row.id);
    installTargets.value = res.data || [];
  } catch {
    installTargets.value = [];
  } finally {
    installLoading.value = false;
  }
};

const toggleInstall = async (target: SkillInstallTargetVO) => {
  const row = installSkillRow.value;
  if (!row) return;
  try {
    if (target.installed) {
      await proxy?.$modal.confirm(
        `确认从应用「${target.agentName}」卸载技能「${row.name}」吗?该应用的技能目录与 load_skill 将立即失效。`
      );
      installBusy.value = target.agentId;
      await uninstallSkill(row.id, target.agentId);
      proxy?.$modal.msgSuccess('已卸载');
    } else {
      installBusy.value = target.agentId;
      await installSkill(row.id, target.agentId);
      proxy?.$modal.msgSuccess('已安装');
    }
    await loadInstallTargets();
  } catch (e) {
    // 用户取消或失败
  } finally {
    installBusy.value = '';
  }
};

const handleStatusChange = async (row: SkillVO, val: string | number | boolean) => {
  const next = val ? '0' : '1';
  try {
    await proxy?.$modal.confirm(
      next === '1'
        ? `确认启用技能「${row.name}」吗?已安装它的应用将恢复可见。`
        : `确认停用技能「${row.name}」吗?已安装它的应用将不再展示目录,load_skill 也会拒绝加载。`
    );
    await changeSkillStatus(row.id, next);
    proxy?.$modal.msgSuccess(next === '0' ? '启用成功' : '停用成功');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleDelete = async (row: SkillVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除技能「${row.name}」吗?全部安装绑定将一并解除。`);
    await delSkill(row.id);
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
.install-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.install-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
