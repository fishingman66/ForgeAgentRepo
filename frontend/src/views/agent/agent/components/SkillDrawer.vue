<template>
  <el-drawer v-model="visible" :title="`技能配置 · ${agentName}`" size="700px" :append-to-body="true" destroy-on-close>
    <div class="toolbar">
      <el-select v-model="addSkillId" filterable clearable placeholder="选择要安装的技能" style="width: 300px" :loading="candidatesLoading">
        <el-option v-for="s in addOptions" :key="s.id" :label="`${s.name} · ${s.description || '无摘要'}`" :value="s.id" />
      </el-select>
      <el-button type="primary" :loading="busy === addSkillId && !!addSkillId" :disabled="!addSkillId" @click="doInstall">添加技能</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      <template #title>
        技能只把一行摘要写进系统提示词,操作手册由模型调用 load_skill 按需加载;卸载或停用后立即失效。
      </template>
    </el-alert>
    <el-table :data="rows" size="small" border v-loading="loading" empty-text="尚未安装任何技能">
      <el-table-column label="技能" prop="name" min-width="120" show-overflow-tooltip />
      <el-table-column label="一行摘要" prop="description" min-width="180" show-overflow-tooltip />
      <el-table-column label="版本" width="70" align="center">
        <template #default="scope">v{{ scope.row.version ?? 1 }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="scope">
          <el-tag size="small" :type="scope.row.status === '0' ? 'success' : 'info'" effect="plain">
            {{ scope.row.status === '0' ? '正常' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="安装时间" width="160" align="center">
        <template #default="scope">
          <span v-if="scope.row.installTime">{{ scope.row.installTime }}</span>
          <span v-else style="color: var(--el-text-color-secondary)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="scope">
          <el-button
            size="small"
            text
            type="danger"
            :loading="busy === scope.row.skillId"
            @click="doUninstall(scope.row as AgentSkillBindVO)"
          >卸载</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-drawer>
</template>

<script setup lang="ts" name="AgentSkillDrawer">
import { ElMessage, ElMessageBox } from 'element-plus';
import { installSkill, listAgentSkills, listSkill, uninstallSkill } from '@/api/agent/skill';
import type { AgentSkillBindVO, SkillVO } from '@/api/agent/skill/types';

const props = defineProps<{ modelValue: boolean; agentId: number | string; agentName: string }>();
const emit = defineEmits<{ 'update:modelValue': [boolean] }>();
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) });

const loading = ref(false);
const candidatesLoading = ref(false);
const rows = ref<AgentSkillBindVO[]>([]);
const candidates = ref<SkillVO[]>([]);
const addSkillId = ref('');
const busy = ref('');

/** 候选 = 可见且正常状态 − 已安装 */
const addOptions = computed(() => {
  const installed = new Set(rows.value.map((r) => r.skillId));
  return candidates.value.filter((s) => s.status === '0' && !installed.has(s.id));
});

const load = async () => {
  if (!props.agentId) return;
  loading.value = true;
  try {
    const res = await listAgentSkills(props.agentId);
    rows.value = res.data || [];
  } finally {
    loading.value = false;
  }
  await loadCandidates();
};

const loadCandidates = async () => {
  candidatesLoading.value = true;
  try {
    const res = await listSkill({ pageNum: 1, pageSize: 200, keyword: '', status: '0' });
    candidates.value = res.data?.rows || [];
  } catch {
    candidates.value = [];
  } finally {
    candidatesLoading.value = false;
  }
};

const doInstall = async () => {
  if (!addSkillId.value) return;
  busy.value = addSkillId.value;
  try {
    await installSkill(addSkillId.value, props.agentId);
    ElMessage.success('已安装,下一轮对话生效');
    addSkillId.value = '';
    await load();
  } finally {
    busy.value = '';
  }
};

const doUninstall = async (row: AgentSkillBindVO) => {
  try {
    await ElMessageBox.confirm(
      `确认卸载技能「${row.name}」?该应用的技能目录与 load_skill 将立即失效。`,
      '卸载确认',
      { type: 'warning' }
    );
    busy.value = row.skillId;
    await uninstallSkill(row.skillId, props.agentId);
    ElMessage.success('已卸载');
    await load();
  } catch (e) {
    // 用户取消或失败
  } finally {
    busy.value = '';
  }
};

watch(
  () => [props.modelValue, props.agentId] as const,
  ([open]) => {
    if (open && props.agentId) {
      load();
    }
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
</style>
