<template>
  <el-drawer v-model="visible" :title="`版本与发布门禁 · ${agentName}`" size="720px" :append-to-body="true" destroy-on-close>
    <el-tabs v-model="tab">
      <el-tab-pane label="版本历史" name="versions">
        <div class="toolbar">
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          <span class="tip">发布与回滚都会生成不可变快照,历史不会被覆盖</span>
        </div>
        <el-table :data="versions" size="small" border v-loading="loading">
          <el-table-column prop="version_no" label="版本" width="80">
            <template #default="{ row }">v{{ row.version_no }}</template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="100" />
          <el-table-column prop="remark" label="说明" show-overflow-tooltip />
          <el-table-column prop="config_hash" label="摘要" width="110">
            <template #default="{ row }">{{ (row.config_hash || '').slice(0, 8) }}</template>
          </el-table-column>
          <el-table-column prop="create_time" label="时间" width="160" />
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="showDetail(row)">详情</el-button>
              <el-button size="small" text @click="showDiff(row)">对比</el-button>
              <el-button size="small" text type="warning" @click="doRollback(row)">回滚</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="发布门禁" name="gate">
        <div class="toolbar">
          <el-button size="small" type="primary" :loading="gateLoading" @click="doPreview">执行预检</el-button>
          <span class="tip">缺策略时用平台默认阈值;豁免只跳过指标项,基础检查始终执行</span>
        </div>
        <div v-if="gate" class="gate-card" :class="gate.passed ? 'ok' : 'bad'">
          <div class="gate-title">{{ gate.passed ? '预检通过' : '预检未通过' }} · {{ gate.reason }}</div>
          <div class="checks">
            <div v-for="(value, key) in gate.checks" :key="key" class="check-item">
              <span>{{ key }}</span><b>{{ formatValue(value) }}</b>
            </div>
          </div>
        </div>
        <div class="section-title">最近门禁执行</div>
        <el-table :data="executions" size="small" border max-height="220">
          <el-table-column prop="status" label="结果" width="90" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column prop="create_time" label="时间" width="160" />
        </el-table>
        <div class="section-title">门禁豁免</div>
        <div class="toolbar">
          <el-input v-model="waiverReason" size="small" placeholder="豁免理由(必填)" style="width: 320px" />
          <el-button size="small" @click="applyWaiver">提交申请</el-button>
        </div>
        <el-table :data="waivers" size="small" border max-height="180">
          <el-table-column prop="reason" label="理由" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button v-if="row.status === 'pending'" size="small" text type="success" @click="approve(row, true)">批准</el-button>
              <el-button v-if="row.status === 'pending'" size="small" text type="danger" @click="approve(row, false)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detailVisible" title="版本明细 / 差异" width="620px" :append-to-body="true">
      <pre class="json-pre">{{ detailText }}</pre>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts" name="AgentVersionDrawer">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  approveWaiver, diffVersions, getVersion, listGateExecutions, listVersions,
  listWaivers, previewGate, requestWaiver, rollbackVersion,
  type GateResult, type VersionItem
} from '@/api/agent/governance';
import { formatCheckValue } from '@/utils/governance';

const props = defineProps<{ modelValue: boolean; agentId: number | string; agentName: string }>();
const emit = defineEmits<{ 'update:modelValue': [boolean]; rolledBack: [] }>();
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) });

const tab = ref('versions');
const loading = ref(false);
const gateLoading = ref(false);
const versions = ref<VersionItem[]>([]);
const executions = ref<any[]>([]);
const waivers = ref<any[]>([]);
const gate = ref<GateResult | null>(null);
const waiverReason = ref('');
const detailVisible = ref(false);
const detailText = ref('');

const load = async () => {
  if (!props.agentId) return;
  loading.value = true;
  try {
    const res = await listVersions(props.agentId);
    versions.value = res.data ?? [];
  } finally {
    loading.value = false;
  }
};

const loadGate = async () => {
  const [exec, wav] = await Promise.all([
    listGateExecutions(props.agentId), listWaivers(props.agentId)
  ]);
  executions.value = exec.data ?? [];
  waivers.value = wav.data ?? [];
};

watch(() => [props.modelValue, props.agentId] as const, ([open]) => {
  if (open && props.agentId) {
    load();
    loadGate();
  }
}, { immediate: true });

const doPreview = async () => {
  gateLoading.value = true;
  try {
    const res = await previewGate(props.agentId);
    gate.value = res.data ?? null;
    await loadGate();
  } finally {
    gateLoading.value = false;
  }
};

const showDetail = async (raw: any) => {
  const row = raw as VersionItem;
  const res = await getVersion(props.agentId, row.version_no);
  detailText.value = JSON.stringify(res.data ?? {}, null, 2);
  detailVisible.value = true;
};

const showDiff = async (raw: any) => {
  const row = raw as VersionItem;
  const current = versions.value[0];
  if (!current || current.version_no === row.version_no) {
    ElMessage.info('请选择与当前版本不同的历史版本进行对比');
    return;
  }
  const res = await diffVersions(props.agentId, row.version_no, current.version_no);
  detailText.value = JSON.stringify(res.data ?? {}, null, 2);
  detailVisible.value = true;
};

const doRollback = async (raw: any) => {
  const row = raw as VersionItem;
  await ElMessageBox.confirm(
    `将 Agent 配置回滚到 v${row.version_no}?回滚会生成一条新版本快照,当前历史不会被删除;已发布 Agent 需先停用。`,
    '回滚确认', { type: 'warning' }
  );
  await rollbackVersion(props.agentId, row.version_no);
  ElMessage.success('已回滚');
  emit('rolledBack');
  await load();
};

const applyWaiver = async () => {
  if (!waiverReason.value.trim()) {
    ElMessage.warning('请填写豁免理由');
    return;
  }
  await requestWaiver(props.agentId, waiverReason.value.trim());
  waiverReason.value = '';
  ElMessage.success('豁免申请已提交,需另一名管理员审批');
  await loadGate();
};

const approve = async (row: any, ok: boolean) => {
  await approveWaiver(row.id, ok);
  ElMessage.success(ok ? '已批准豁免' : '已驳回');
  await loadGate();
};

const formatValue = formatCheckValue;
</script>

<style scoped lang="scss">
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.tip { font-size: 12px; color: var(--el-text-color-secondary); }
.section-title { margin: 18px 0 10px; font-size: 13px; font-weight: 500; }
.gate-card { padding: 12px; border-radius: 10px; border: 1px solid var(--el-border-color-lighter); margin-bottom: 12px; }
.gate-card.ok { border-color: var(--el-color-success-light-5); }
.gate-card.bad { border-color: var(--el-color-danger-light-5); }
.gate-title { font-size: 13px; font-weight: 500; margin-bottom: 10px; }
.checks { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px 16px; }
.check-item { display: flex; gap: 8px; font-size: 12px; span { color: var(--el-text-color-secondary); min-width: 130px; } b { font-weight: 500; word-break: break-all; } }
.json-pre { max-height: 420px; overflow: auto; padding: 12px; font-size: 12px; line-height: 1.5; border-radius: 8px; background: var(--el-fill-color-light); white-space: pre-wrap; word-break: break-all; }
</style>
