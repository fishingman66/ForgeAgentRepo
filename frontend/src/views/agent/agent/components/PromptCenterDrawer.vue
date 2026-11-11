<template>
  <el-drawer v-model="visible" title="Prompt 模板中心" size="820px" :append-to-body="true" destroy-on-close>
    <div class="layout">
      <div class="left">
        <div class="toolbar">
          <el-button size="small" type="primary" @click="startNew">新建模板</el-button>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
        <el-table :data="templates" size="small" border highlight-current-row max-height="520" @row-click="select">
          <el-table-column prop="name" label="模板" show-overflow-tooltip />
          <el-table-column prop="category" label="类别" width="80" />
          <el-table-column prop="status" label="状态" width="80" />
        </el-table>
      </div>
      <div class="right">
        <template v-if="form.id || creating">
          <el-form label-width="90px" size="small">
            <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
            <el-form-item label="类别">
              <el-select v-model="form.category" style="width: 100%">
                <el-option v-for="c in ['system', 'user', 'rag', 'tool', 'evaluation', 'workflow']" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <el-form-item label="描述"><el-input v-model="form.description" maxlength="500" /></el-form-item>
            <el-form-item label="模板内容">
              <el-input v-model="form.content" type="textarea" :rows="7" placeholder="支持双花括号变量名占位符,如 tone" />
            </el-form-item>
            <el-form-item label="变量契约">
              <el-input v-model="form.variableSchema" type="textarea" :rows="4" placeholder='{"vars":[{"name":"tone","type":"string","required":true,"default":"专业"}]}' />
            </el-form-item>
          </el-form>
          <div class="toolbar">
            <el-button size="small" type="primary" @click="save">保存模板</el-button>
            <el-button v-if="form.id" size="small" @click="publish">发布新版本</el-button>
          </div>

          <template v-if="form.id">
            <div class="section-title">版本({{ versions.length }})</div>
            <el-table :data="versions" size="small" border max-height="200">
              <el-table-column prop="version_no" label="版本" width="70">
                <template #default="{ row }">v{{ row.version_no }}</template>
              </el-table-column>
              <el-table-column prop="content_hash" label="摘要" width="110">
                <template #default="{ row }">{{ (row.content_hash || '').slice(0, 8) }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="说明" show-overflow-tooltip />
              <el-table-column label="操作" width="170">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="preview(row)">预览</el-button>
                  <el-button size="small" text type="warning" @click="makeStable(row)">设为稳定</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="toolbar" style="margin-top: 10px">
              <el-input v-model="variablesText" size="small" placeholder='变量值 JSON,如 {"tone":"口语"}' style="width: 300px" />
              <el-checkbox v-model="asStable" size="small">发布后设为稳定版</el-checkbox>
            </div>
          </template>

          <pre v-if="previewText" class="json-pre">{{ previewText }}</pre>
        </template>
        <el-empty v-else description="左侧选择模板,或新建" />
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts" name="PromptCenterDrawer">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getPromptTemplate, getPromptVersion, listPromptTemplates, listPromptVersions,
  previewPrompt, publishPromptVersion, savePromptTemplate, setPromptStable,
  type PromptTemplate, type PromptVersion
} from '@/api/agent/governance';
import { parseVariableContract } from '@/utils/governance';

const props = defineProps<{ modelValue: boolean; workspaceId?: number | string }>();
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>();
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) });

const templates = ref<PromptTemplate[]>([]);
const versions = ref<PromptVersion[]>([]);
const creating = ref(false);
const asStable = ref(true);
const variablesText = ref('{}');
const previewText = ref('');
const form = ref<Record<string, any>>({ id: null, name: '', description: '', category: 'system', content: '', variableSchema: '' });

const load = async () => {
  const res = await listPromptTemplates(props.workspaceId);
  templates.value = res.data ?? [];
};

watch(() => props.modelValue, (open) => {
  if (open) load();
}, { immediate: true });

const select = async (row: PromptTemplate) => {
  const res = await getPromptTemplate(row.id);
  const d = res.data ?? row;
  form.value = {
    id: d.id, name: d.name, description: d.description ?? '', category: d.category ?? 'system',
    content: d.content ?? '', variableSchema: d.variable_schema ?? ''
  };
  creating.value = false;
  previewText.value = '';
  const v = await listPromptVersions(d.id);
  versions.value = v.data ?? [];
};

const startNew = () => {
  form.value = { id: null, name: '', description: '', category: 'system', content: '', variableSchema: '' };
  versions.value = [];
  creating.value = true;
  previewText.value = '';
};

const save = async () => {
  if (!form.value.name?.trim() || !form.value.content?.trim()) {
    ElMessage.warning('名称与模板内容不能为空');
    return;
  }
  const res = await savePromptTemplate({ ...form.value, workspaceId: props.workspaceId });
  form.value.id = res.data;
  creating.value = false;
  emit('changed');
  ElMessage.success('模板已保存');
  await load();
  await select({ id: Number(res.data) } as PromptTemplate);
};

const publish = async () => {
  if (!form.value.content?.trim()) {
    ElMessage.warning('模板内容不能为空');
    return;
  }
  await publishPromptVersion({
    templateId: form.value.id, content: form.value.content,
    variableSchema: form.value.variableSchema || null,
    remark: '前端发布', asStable: asStable.value
  });
  ElMessage.success('新版本已发布' + (asStable.value ? ',并设为稳定版' : ''));
  const v = await listPromptVersions(form.value.id);
  versions.value = v.data ?? [];
  await load();
};

const makeStable = async (raw: any) => {
  const row = raw as PromptVersion;
  await setPromptStable(form.value.id, row.id);
  ElMessage.success(`v${row.version_no} 已设为稳定版`);
  await load();
};

const preview = async (raw: any) => {
  const row = raw as PromptVersion;
  const version = await getPromptVersion(row.id);
  const res = await previewPrompt(row.id, variablesText.value || '{}');
  const contract = parseVariableContract(version.data?.variable_schema || '');
  const contractText = contract.length
    ? contract.map((v) => `${v.name}(${v.type}${v.required ? ',必填' : ''})`).join(' / ')
    : '(无)';
  previewText.value = [
    `模板版本: v${row.version_no}`,
    `变量契约: ${contractText}`,
    `原始契约: ${version.data?.variable_schema || '(无)'}`,
    `内容摘要: ${res.data?.contentHash ?? ''}`,
    `装配层: ${(res.data?.layers ?? []).join(' / ')}`,
    '',
    res.data?.content ?? ''
  ].join('\n');
};
</script>

<style scoped lang="scss">
.layout { display: grid; grid-template-columns: 300px 1fr; gap: 16px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.section-title { margin: 16px 0 8px; font-size: 13px; font-weight: 500; }
.json-pre { margin-top: 12px; max-height: 260px; overflow: auto; padding: 12px; font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; border-radius: 8px; background: var(--el-fill-color-light); }
</style>
