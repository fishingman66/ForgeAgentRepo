<template>
  <el-drawer v-model="visible" title="Agent 效果评测" size="760px" :append-to-body="true" destroy-on-close>
    <el-tabs v-model="tab">
      <el-tab-pane label="评测集与运行" name="dataset">
        <div class="toolbar">
          <el-select v-model="datasetId" placeholder="选择评测集" size="small" style="width: 220px" @change="loadCases">
            <el-option v-for="d in datasets" :key="d.id" :label="`${d.name} (${d.case_count} 条)`" :value="d.id" />
          </el-select>
          <el-button size="small" @click="newVisible = true">新建评测集</el-button>
          <el-button size="small" type="primary" :loading="running" :disabled="!datasetId" @click="doRun">运行评测</el-button>
          <el-switch v-model="judgeEnabled" size="small" active-text="LLM 裁判" />
        </div>
        <div class="tip">样本上限 50 条;评测会话自动标记为临时会话,不进正式会话列表。开启裁判时评分为规则分与裁判分均值,裁判失败自动回退规则分。</div>

        <div v-if="lastResult" class="result-card">
          <div class="result-title">本次评测 · 任务 #{{ lastResult.runId }}</div>
          <div class="metrics">
            <div><span>平均得分</span><b>{{ lastResult.avgScore?.toFixed?.(1) ?? lastResult.avgScore }}</b></div>
            <div><span>样本数</span><b>{{ lastResult.totalCases }}</b></div>
            <div><span>P95 耗时</span><b>{{ lastResult.p95LatencyMs }} ms</b></div>
            <div><span>拒答率</span><b>{{ lastResult.refusalRate?.toFixed?.(1) ?? lastResult.refusalRate }}%</b></div>
          </div>
          <el-button size="small" text type="primary" @click="setBaseline(lastResult.runId)">设为黄金基线</el-button>
          <el-button size="small" text @click="tab = 'runs'">查看逐样本结果</el-button>
        </div>

        <div class="section-title">样本导入(JSON 数组,元素含 question / expectedKeywords / referenceAnswer)</div>
        <el-input v-model="casesText" type="textarea" :rows="5" placeholder='[{"question":"订单怎么退款?","expectedKeywords":"退款,订单"}]' />
        <div class="toolbar" style="margin-top: 8px">
          <el-button size="small" :disabled="!datasetId" @click="doImport">导入样本</el-button>
          <span class="tip">当前数据集 {{ cases.length }} 条样本</span>
        </div>
        <el-table :data="cases" size="small" border max-height="220">
          <el-table-column prop="question" label="问题" show-overflow-tooltip />
          <el-table-column prop="expected_keywords" label="关键词" width="180" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="运行记录" name="runs">
        <div class="toolbar"><el-button size="small" @click="loadRuns">刷新</el-button></div>
        <el-table :data="runs" size="small" border max-height="240" @row-click="showRun">
          <el-table-column prop="id" label="任务" width="70" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="avg_score" label="得分" width="90" />
          <el-table-column prop="p95_latency_ms" label="P95(ms)" width="100" />
          <el-table-column prop="total_cases" label="样本" width="70" />
          <el-table-column prop="create_time" label="时间" width="160" />
        </el-table>
        <div class="section-title">逐样本结果</div>
        <el-table :data="results" size="small" border max-height="280">
          <el-table-column prop="question" label="问题" show-overflow-tooltip />
          <el-table-column prop="score" label="得分" width="80" />
          <el-table-column prop="judge_score" label="裁判分" width="80" />
          <el-table-column prop="latency_ms" label="耗时" width="90" />
          <el-table-column prop="answer" label="回答" show-overflow-tooltip />
          <el-table-column prop="error_message" label="异常" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="newVisible" title="新建评测集" width="420px" :append-to-body="true">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="newForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="newForm.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts" name="AgentEvalDrawer">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  addEvalCases, createBaseline, createEvalDataset, getEvalRun, listEvalCases,
  listEvalDatasets, listEvalRuns, runEval,
  type EvalCase, type EvalDataset, type EvalRun
} from '@/api/agent/governance';

const props = defineProps<{ modelValue: boolean; agentId: number | string; workspaceId?: number | string }>();
const emit = defineEmits<{ 'update:modelValue': [boolean] }>();
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) });

const tab = ref('dataset');
const datasets = ref<EvalDataset[]>([]);
const datasetId = ref<number | null>(null);
const cases = ref<EvalCase[]>([]);
const casesText = ref('');
const judgeEnabled = ref(false);
const running = ref(false);
const lastResult = ref<any>(null);
const runs = ref<EvalRun[]>([]);
const results = ref<any[]>([]);
const newVisible = ref(false);
const newForm = ref({ name: '', description: '' });

const loadDatasets = async (keepSelection = true) => {
  const res = await listEvalDatasets(props.workspaceId);
  datasets.value = res.data ?? [];
  if (!keepSelection || !datasets.value.some((d) => d.id === datasetId.value)) {
    datasetId.value = datasets.value[0]?.id ?? null;
  }
  if (datasetId.value) await loadCases();
};

const loadCases = async () => {
  if (!datasetId.value) return;
  const res = await listEvalCases(datasetId.value);
  cases.value = res.data ?? [];
};

const loadRuns = async () => {
  const res = await listEvalRuns(props.agentId);
  runs.value = res.data ?? [];
};

watch(() => [props.modelValue, props.agentId] as const, ([open]) => {
  if (open && props.agentId) {
    loadDatasets(false);
    loadRuns();
  }
}, { immediate: true });

const doCreate = async () => {
  if (!newForm.value.name.trim()) {
    ElMessage.warning('请填写评测集名称');
    return;
  }
  const res = await createEvalDataset({
    name: newForm.value.name.trim(), description: newForm.value.description,
    workspaceId: props.workspaceId, agentId: props.agentId
  });
  newVisible.value = false;
  newForm.value = { name: '', description: '' };
  datasetId.value = Number(res.data);
  await loadDatasets(true);
};

const doImport = async () => {
  let parsed: any[];
  try {
    parsed = JSON.parse(casesText.value);
    if (!Array.isArray(parsed)) throw new Error('not array');
  } catch {
    ElMessage.error('样本必须是 JSON 数组');
    return;
  }
  const res = await addEvalCases(datasetId.value!, parsed);
  ElMessage.success(`已导入 ${res.data} 条样本`);
  casesText.value = '';
  await loadCases();
  await loadDatasets(true);
};

const doRun = async () => {
  running.value = true;
  try {
    const res = await runEval(datasetId.value!, props.agentId, judgeEnabled.value);
    lastResult.value = res.data;
    ElMessage.success(`评测完成,平均得分 ${Number(res.data?.avgScore ?? 0).toFixed(1)}`);
    await loadRuns();
  } finally {
    running.value = false;
  }
};

const showRun = async (row: EvalRun) => {
  const res = await getEvalRun(row.id);
  results.value = res.data?.results ?? [];
  lastResult.value = { ...res.data?.run, runId: row.id };
};

const setBaseline = async (runId: number) => {
  await createBaseline(runId);
  ElMessage.success('已设为黄金基线,后续发布会检查退化');
};
</script>

<style scoped lang="scss">
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.tip { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 12px; }
.section-title { margin: 18px 0 10px; font-size: 13px; font-weight: 500; }
.result-card { padding: 12px; margin-top: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.result-title { font-size: 13px; font-weight: 500; margin-bottom: 10px; }
.metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 10px; }
.metrics div { display: flex; flex-direction: column; gap: 4px; span { font-size: 11px; color: var(--el-text-color-secondary); } b { font-size: 15px; font-weight: 500; } }
</style>
