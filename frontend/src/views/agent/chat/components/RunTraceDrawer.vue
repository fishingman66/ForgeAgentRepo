<template>
  <el-drawer v-model="visible" title="运行链路" size="580px" :append-to-body="true" destroy-on-close>
    <div v-loading="loading" class="run-trace">
      <template v-if="detail?.run">
        <div class="run-summary">
          <div class="run-kv"><span>运行 ID</span><b class="mono">{{ detail.run.run_id }}</b></div>
          <div class="run-kv"><span>状态</span><b :class="statusClass">{{ detail.run.status }}</b></div>
          <div class="run-kv"><span>类型 / 模型</span><b>{{ detail.run.run_type }} · {{ detail.run.model_name || '—' }}</b></div>
          <div class="run-kv"><span>总耗时</span><b>{{ fmtMs(detail.run.latency_ms) }}</b></div>
          <div class="run-kv"><span>首 Token</span><b>{{ fmtMs(detail.run.first_token_latency_ms) }}</b></div>
          <div class="run-kv"><span>Token</span><b>{{ detail.run.total_tokens ?? 0 }}（入 {{ detail.run.prompt_tokens ?? 0 }} / 出 {{ detail.run.completion_tokens ?? 0 }}）</b></div>
          <div v-if="detail.run.error_message" class="run-kv"><span>错误</span><b class="err">{{ detail.run.error_message }}</b></div>
        </div>

        <div class="run-steps-title">执行步骤（{{ detail.steps.length }}）</div>
        <el-timeline v-if="detail.steps.length">
          <el-timeline-item
            v-for="s in detail.steps" :key="s.step_no"
            :timestamp="`#${s.step_no} · ${fmtMs(s.latency_ms)}`" placement="top"
            :color="stepColor(s.step_type)"
          >
            <div class="step-card">
              <div class="step-head">
                <el-tag size="small" effect="plain">{{ s.step_type }}</el-tag>
                <span class="step-name">{{ s.step_name }}</span>
                <span v-if="s.token_usage" class="step-tokens">{{ s.token_usage }} tok</span>
              </div>
              <pre v-if="s.payload" class="step-payload">{{ pretty(s.payload) }}</pre>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无步骤明细" :image-size="60" />
      </template>
      <el-empty v-else-if="!loading" description="暂无该运行的链路数据" />
    </div>
  </el-drawer>
</template>

<script setup lang="ts" name="RunTraceDrawer">
import { computed, ref, watch } from 'vue';
import { getRunDetail, type RunDetailVO } from '@/api/agent/session';

const props = defineProps<{ modelValue: boolean; runId: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
});
const loading = ref(false);
const detail = ref<RunDetailVO | null>(null);

watch(
  () => [props.modelValue, props.runId] as const,
  async ([open]) => {
    if (!open || !props.runId) return;
    loading.value = true;
    detail.value = null;
    try {
      const res = await getRunDetail(props.runId);
      detail.value = res.data ?? null;
    } finally {
      loading.value = false;
    }
  },
  { immediate: true }
);

const statusClass = computed(() => (detail.value?.run?.status === 'success' ? 'ok' : 'err'));

const fmtMs = (value: number | null | undefined) => (value == null ? '—' : value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(2)} s`);

const stepColor = (type: string) => {
  if (type === 'ERROR') return 'var(--el-color-danger)';
  if (type === 'LLM') return 'var(--el-color-primary)';
  if (type === 'RETRIEVAL') return 'var(--el-color-warning)';
  return 'var(--el-color-info)';
};

const pretty = (payload: string) => {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
};
</script>

<style scoped lang="scss">
.run-trace {
  padding: 0 4px 24px;
}
.run-summary {
  display: grid;
  gap: 8px;
  padding: 14px;
  margin-bottom: 20px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-fill-color-blank);
}
.run-kv {
  display: flex;
  gap: 12px;
  font-size: 12px;
  line-height: 1.6;
  span {
    width: 84px;
    flex: none;
    color: var(--el-text-color-secondary);
  }
  b {
    font-weight: 500;
    color: var(--el-text-color-primary);
    word-break: break-all;
  }
  .mono {
    font-family: var(--font-mono, monospace);
    font-size: 11px;
  }
  .ok {
    color: var(--el-color-success);
  }
  .err {
    color: var(--el-color-danger);
  }
}
.run-steps-title {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 14px;
  color: var(--el-text-color-primary);
}
.step-card {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}
.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.step-name {
  font-size: 12px;
  color: var(--el-text-color-primary);
}
.step-tokens {
  margin-left: auto;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.step-payload {
  margin: 8px 0 0;
  padding: 8px;
  max-height: 180px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
}
</style>
