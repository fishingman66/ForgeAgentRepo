<template>
  <el-dialog v-model="visible" title="创建 Agent（向导）" width="760px" destroy-on-close @closed="handleClosed">
    <el-steps :active="step" align-center finish-status="success" class="wizard-steps">
      <el-step title="命名" />
      <el-step title="选模型" />
      <el-step title="提示词" />
      <el-step title="能力" />
      <el-step title="创建" />
    </el-steps>

    <div class="wizard-body">
      <!-- 1 命名 -->
      <div v-show="step === 0" class="wizard-pane">
        <el-form label-width="90px">
          <el-form-item label="所属空间" required>
            <el-select v-model="form.workspaceId" placeholder="请选择工作空间" filterable style="width: 100%">
              <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="名称" required>
            <el-input v-model="form.name" maxlength="50" show-word-limit placeholder="如：售后处理助手" />
          </el-form-item>
          <el-form-item label="用途描述">
            <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit
              placeholder="一句话说明它解决什么问题（会带到提示词生成）" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 2 选模型 -->
      <div v-show="step === 1" class="wizard-pane">
        <el-form label-width="90px">
          <el-form-item label="模型" required>
            <el-select v-model="form.modelId" placeholder="选择模型" filterable style="width: 100%">
              <el-option v-for="m in modelOptions" :key="m.id" :label="`${m.name}（${m.modelName}）`" :value="m.id" />
            </el-select>
            <div class="field-hint">空间私有模型与平台共享模型都可选；密钥由模型配置统一管理</div>
          </el-form-item>
          <el-form-item label="温度">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" style="width: 100%" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 3 提示词 -->
      <div v-show="step === 2" class="wizard-pane">
        <el-alert v-if="!form.modelId" type="warning" :closable="false" show-icon
          title="尚未选择模型，AI 生成不可用；可直接手工填写提示词" />
        <el-form label-width="90px">
          <el-form-item label="业务描述">
            <el-input v-model="form.purpose" type="textarea" :rows="3" maxlength="500" show-word-limit
              placeholder="这个 Agent 要做什么？例如：处理退换货咨询，先核对订单再给处理方案" />
          </el-form-item>
          <el-form-item label="语气风格">
            <el-input v-model="form.tone" maxlength="60" placeholder="如：专业简洁、耐心亲和" />
          </el-form-item>
          <el-form-item label="边界">
            <el-input v-model="form.boundaries" type="textarea" :rows="2" maxlength="300"
              placeholder="禁止事项，如：不承诺赔偿金额、不透露内部流程" />
          </el-form-item>
          <el-form-item>
            <el-button :loading="generating" :disabled="!form.modelId" icon="MagicStick" @click="generate">
              生成提示词草稿
            </el-button>
            <span class="field-hint" style="margin-left: 8px">生成后可直接编辑，确认后才写入</span>
          </el-form-item>
          <el-form-item label="系统提示词">
            <el-input v-model="form.systemPrompt" type="textarea" :rows="10" maxlength="5000" show-word-limit
              placeholder="Agent 的系统提示词（人设 / 任务 / 约束 / 输出格式）" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 4 能力 -->
      <div v-show="step === 3" class="wizard-pane">
        <el-form label-width="90px">
          <el-form-item label="知识库">
            <el-select v-model="form.knowledgeIds" multiple filterable clearable placeholder="可选，绑定检索知识库"
              style="width: 100%">
              <el-option v-for="k in knowledgeOptions" :key="k.id" :label="k.name" :value="k.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="工具">
            <el-select v-model="form.toolIds" multiple filterable clearable placeholder="可选，绑定可调用工具"
              style="width: 100%">
              <el-option v-for="t in toolOptions" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
          </el-form-item>
          <el-alert type="info" :closable="false" show-icon
            title="技能、工作流等进阶配置请创建后到高级设置里补充，向导不覆盖原有表单" />
        </el-form>
      </div>

      <!-- 5 创建 -->
      <div v-show="step === 4" class="wizard-pane">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="名称">{{ form.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="所属空间">{{ workspaceName }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ modelName }}</el-descriptions-item>
          <el-descriptions-item label="提示词">{{ promptSummary }}</el-descriptions-item>
          <el-descriptions-item label="知识库 / 工具">
            {{ form.knowledgeIds.length }} 个知识库 / {{ form.toolIds.length }} 个工具
          </el-descriptions-item>
        </el-descriptions>
        <el-alert type="success" :closable="false" show-icon style="margin-top: 12px"
          title="保存为草稿可继续在高级设置里完善；创建并发布后立即出现在可选 Agent 列表" />
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button v-if="step > 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 4" type="primary" @click="next">下一步</el-button>
      <el-button v-if="step === 4" :loading="submitting" @click="submit('0')">保存为草稿</el-button>
      <el-button v-if="step === 4" type="primary" :loading="submitting" @click="submit('1')">创建并发布</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="AgentCreateWizard">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { addAgent } from '@/api/agent/agent';
import { generatePromptDraft } from '@/api/agent/governance';
import type { AgentForm } from '@/api/agent/agent/types';

interface WizardForm {
  workspaceId?: number | string;
  name: string;
  description: string;
  modelId?: number | string;
  temperature: number;
  purpose: string;
  tone: string;
  boundaries: string;
  systemPrompt: string;
  knowledgeIds: Array<number | string>;
  toolIds: Array<number | string>;
}

const props = defineProps<{
  modelValue: boolean;
  workspaceOptions?: Array<Record<string, any>>;
  modelOptions?: Array<Record<string, any>>;
  knowledgeOptions?: Array<Record<string, any>>;
  toolOptions?: Array<Record<string, any>>;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'created'): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
});

const step = ref(0);
const generating = ref(false);
const submitting = ref(false);

const emptyForm = (): WizardForm => ({
  workspaceId: undefined,
  name: '',
  description: '',
  modelId: undefined,
  temperature: 0.7,
  purpose: '',
  tone: '',
  boundaries: '',
  systemPrompt: '',
  knowledgeIds: [],
  toolIds: []
});

const form = ref<WizardForm>(emptyForm());

const workspaceName = computed(() => {
  const hit = (props.workspaceOptions || []).find((w) => String(w.id) === String(form.value.workspaceId));
  return hit?.name || '-';
});

const modelName = computed(() => {
  const hit = (props.modelOptions || []).find((m) => String(m.id) === String(form.value.modelId));
  return hit ? `${hit.name}（${hit.modelName}）` : '-';
});

const promptSummary = computed(() => {
  const text = (form.value.systemPrompt || '').trim();
  if (!text) return '未填写（可在高级设置补充）';
  return text.length > 60 ? text.slice(0, 60) + '…' : text;
});

const next = () => {
  if (step.value === 0) {
    if (!form.value.workspaceId) {
      ElMessage.warning('请选择所属空间');
      return;
    }
    if (!form.value.name || form.value.name.trim().length < 2) {
      ElMessage.warning('名称至少 2 个字符');
      return;
    }
  }
  if (step.value === 1 && !form.value.modelId) {
    ElMessage.warning('请选择模型');
    return;
  }
  step.value++;
};

const generate = async () => {
  if (!form.value.modelId || !form.value.workspaceId) {
    ElMessage.warning('请先选择空间与模型');
    return;
  }
  if ((form.value.purpose || '').trim().length < 5) {
    ElMessage.warning('请先用至少 5 个字描述业务用途');
    return;
  }
  generating.value = true;
  try {
    const res = await generatePromptDraft({
      workspaceId: form.value.workspaceId,
      modelId: form.value.modelId,
      agentName: form.value.name,
      purpose: form.value.purpose,
      tone: form.value.tone,
      boundaries: form.value.boundaries
    });
    const result = res.data;
    if (!result?.ok) {
      ElMessage.warning(result?.message || '生成失败，可手工填写提示词');
      return;
    }
    form.value.systemPrompt = result.content || '';
    ElMessage.success('草稿已生成，可直接编辑后使用');
  } catch (e: any) {
    ElMessage.error(e?.message || '生成失败');
  } finally {
    generating.value = false;
  }
};

const submit = async (status: string) => {
  submitting.value = true;
  try {
    const payload: AgentForm = {
      workspaceId: form.value.workspaceId,
      name: form.value.name.trim(),
      description: form.value.description,
      modelId: form.value.modelId,
      temperature: form.value.temperature,
      systemPrompt: form.value.systemPrompt,
      knowledgeIds: form.value.knowledgeIds,
      toolIds: form.value.toolIds,
      promptBindingMode: 'MANUAL',
      status
    };
    await addAgent(payload);
    ElMessage.success(status === '1' ? '已创建并发布' : '已保存为草稿');
    emit('created');
    visible.value = false;
  } catch (e: any) {
    ElMessage.error(e?.message || '创建失败');
  } finally {
    submitting.value = false;
  }
};

const handleClosed = () => {
  step.value = 0;
  form.value = emptyForm();
};
</script>

<style scoped>
.wizard-steps {
  margin-bottom: 18px;
}
.wizard-body {
  min-height: 320px;
}
.wizard-pane {
  padding: 4px 6px;
}
.field-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
