<script setup lang="ts">
/**
 * 运营总览(C2/C4):文本指标(botSessions/handoffRate/SLA 等)聚合展示。
 * 数据流:listMetrics 按日期区间拉日聚合;rebuildMetrics 手动重算。
 * 扩展点:语音指标(agent_cc_metric_daily)接入本页与通话记录页双入口呈现。
 */
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listMetrics, rebuildMetrics } from '@/api/customerservice';
import type { CsMetricDaily } from '@/api/customerservice';

const rows = ref<CsMetricDaily[]>([]);
const loading = ref(false);
const rebuilding = ref(false);

const cards = [
  { key: 'botSessions', label: '自助会话', suffix: '' },
  { key: 'handoffCount', label: '转人工', suffix: '' },
  { key: 'firstResponseP95Ms', label: '首响 P95', suffix: ' ms' },
  { key: 'csatAvg', label: 'CSAT 均分', suffix: '' }
];

const load = async () => {
  loading.value = true;
  try {
    const res = await listMetrics();
    rows.value = res.data ?? [];
  } catch {
    ElMessage.error('指标加载失败');
  } finally {
    loading.value = false;
  }
};

const rebuild = async () => {
  rebuilding.value = true;
  try {
    await rebuildMetrics();
    ElMessage.success('今日指标已重建');
    await load();
  } catch {
    ElMessage.error('重建失败');
  } finally {
    rebuilding.value = false;
  }
};

const first = () => rows.value[0] as unknown as Record<string, number> | undefined;

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-row :gutter="12">
      <el-col v-for="item in cards" :key="item.key" :span="6">
        <el-card shadow="never">
          <div class="cs-label">{{ item.label }}</div>
          <div class="cs-value">{{ first()?.[item.key] ?? 0 }}{{ item.suffix }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header"><span>日指标</span><el-button type="primary" size="small" :loading="rebuilding" @click="rebuild">重建今日指标</el-button></div>
      </template>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="statDate" label="日期" width="150" />
        <el-table-column prop="botSessions" label="自助会话" width="110" />
        <el-table-column prop="handoffCount" label="转人工" width="100" />
        <el-table-column prop="firstResponseP95Ms" label="首响P95(ms)" width="130" />
        <el-table-column prop="resolveRate" label="解决率(%)" width="120" />
        <el-table-column prop="handoffRate" label="转人工率(%)" width="130" />
        <el-table-column prop="csatAvg" label="CSAT" width="100" />
        <el-table-column prop="csatResponseRate" label="回收率(%)" width="120" />
        <el-table-column prop="slaPassRate" label="SLA达标(%)" width="130" />
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无指标，点击右上角重建" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-card { margin-top: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-label { font-size: 12px; color: var(--el-text-color-secondary); }
.cs-value { font-size: 24px; font-weight: 500; margin-top: 6px; }
</style>
