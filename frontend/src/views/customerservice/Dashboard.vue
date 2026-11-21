<script setup lang="ts">
/**
 * 运营总览(C2/C4 + E1-A4):文本指标 + echarts 图表聚合展示。
 * 数据流:listMetrics 按日期区间拉日聚合(全渠道行 + 每渠道行);rebuildMetrics 手动重算。
 *   ① 会话量/转人工双轴趋势(全渠道行按日期)  ② 渠道会话分布(渠道行)
 *   ③ 服务漏斗(全渠道汇总)——A4 起走独立的 /metrics/funnel 真实计数口,
 *     三段同源于 agent_cs_conversation,不再用"会话数 × 自动解决率"反推的估算值。
 * 扩展点:语音指标(agent_cc_metric_daily)接入本页与通话记录页双入口呈现。
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import * as echarts from 'echarts';
import { ElMessage } from 'element-plus';
import { getFunnel, listMetrics, rebuildMetrics } from '@/api/customerservice';
import type { CsFunnel, CsMetricDaily } from '@/api/customerservice';

const rows = ref<CsMetricDaily[]>([]);
const funnel = ref<CsFunnel>({ sessions: 0, contained: 0, handoff: 0 });
const loading = ref(false);
const rebuilding = ref(false);

/** A4:日期区间与 /metrics 同口径(yyyy-MM-dd);默认近 30 天(含今日)。 */
const dateRange = ref<[string, string] | null>(defaultRange());
function defaultRange(): [string, string] {
  const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const to = new Date();
  const from = new Date(to.getTime() - 29 * 86400_000);
  return [fmt(from), fmt(to)];
}
const rangeParams = () => ({ from: dateRange.value?.[0], to: dateRange.value?.[1] });

const cards = [
  { key: 'botSessions', label: '自助会话', suffix: '' },
  { key: 'handoffCount', label: '转人工', suffix: '' },
  { key: 'firstResponseP95Ms', label: '首响 P95', suffix: ' ms' },
  { key: 'csatAvg', label: 'CSAT 均分', suffix: '' },
  { key: 'containmentRate', label: 'AI 自动解决率', suffix: ' %' },
  { key: 'repeatContactRate', label: '重复进线率', suffix: ' %' }
];

// 全渠道行(channelCode 为空)才用于总量卡与趋势,避免与渠道行重复计数
const overallRows = computed(() =>
  [...rows.value].filter((r) => !r.channelCode).sort((a, b) => a.statDate.localeCompare(b.statDate))
);
const channelRows = computed(() => rows.value.filter((r) => !!r.channelCode));
const first = () => overallRows.value[0] as unknown as Record<string, number> | undefined;

const trendEl = ref<HTMLElement>();
const channelEl = ref<HTMLElement>();
const funnelEl = ref<HTMLElement>();
let trendChart: echarts.ECharts | undefined;
let channelChart: echarts.ECharts | undefined;
let funnelChart: echarts.ECharts | undefined;

/** 容器被 v-if 重建后旧实例绑定的是已分离节点:检测并重新初始化(同平台看板口径) */
const ensureChart = (chart: echarts.ECharts | undefined, el: HTMLElement): echarts.ECharts => {
  if (chart && chart.getDom() !== el) {
    chart.dispose();
    chart = undefined;
  }
  return chart || echarts.init(el);
};

const renderCharts = async () => {
  await nextTick();
  const tr = overallRows.value;
  if (trendEl.value && tr.length) {
    trendChart = ensureChart(trendChart, trendEl.value);
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['自助会话', '转人工'], right: 8, textStyle: { fontSize: 11 } },
      grid: { left: 44, right: 44, top: 32, bottom: 26 },
      xAxis: { type: 'category', data: tr.map((r) => r.statDate.slice(5)), axisLabel: { fontSize: 11 } },
      yAxis: [
        { type: 'value', name: '会话', nameTextStyle: { fontSize: 11 } },
        { type: 'value', name: '转人工', nameTextStyle: { fontSize: 11 } }
      ],
      series: [
        { name: '自助会话', type: 'bar', data: tr.map((r) => r.botSessions), itemStyle: { color: '#2563eb' } },
        { name: '转人工', type: 'line', yAxisIndex: 1, smooth: true,
          data: tr.map((r) => r.handoffCount), itemStyle: { color: '#f59e0b' } }
      ]
    }, true);
  }
  const cr = channelRows.value;
  if (channelEl.value && cr.length) {
    channelChart = ensureChart(channelChart, channelEl.value);
    channelChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, textStyle: { fontSize: 11 } },
      series: [{
        type: 'pie', radius: ['38%', '66%'], center: ['50%', '44%'],
        data: cr.map((r) => ({ name: r.channelCode, value: r.botSessions })),
        label: { fontSize: 11 }
      }]
    }, true);
  }
  // A4:三段取后端真实聚合(同源于会话表),不再由日指标比率反推
  const tot = funnel.value;
  if (funnelEl.value && tot.sessions > 0) {
    funnelChart = ensureChart(funnelChart, funnelEl.value);
    funnelChart.setOption({
      tooltip: { trigger: 'item' },
      series: [{
        type: 'funnel', left: '12%', width: '76%', top: 16, bottom: 16,
        label: { fontSize: 11, formatter: '{b}\n{c}' },
        data: [
          { name: '会话总量', value: tot.sessions },
          { name: 'AI 自动解决', value: tot.contained },
          { name: '转人工', value: tot.handoff }
        ]
      }]
    }, true);
  }
};

const load = async () => {
  loading.value = true;
  try {
    const { from, to } = rangeParams();
    const res = await listMetrics(from, to);
    rows.value = res.data ?? [];
    // 漏斗真实数与指标同源同区间;单独捕获,失败不让整页指标空白
    try {
      const fr = await getFunnel(from, to);
      funnel.value = fr.data ?? { sessions: 0, contained: 0, handoff: 0 };
    } catch {
      funnel.value = { sessions: 0, contained: 0, handoff: 0 };
    }
    await renderCharts();
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

const onResize = () => {
  trendChart?.resize();
  channelChart?.resize();
  funnelChart?.resize();
};

onMounted(() => { load(); window.addEventListener('resize', onResize); });
onUnmounted(() => {
  window.removeEventListener('resize', onResize);
  trendChart?.dispose();
  channelChart?.dispose();
  funnelChart?.dispose();
});
</script>

<template>
  <div class="cs-page">
    <el-row :gutter="12" class="cs-filter">
      <el-col :xs="24" :sm="12" :md="8">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :clearable="false"
          style="width: 100%"
          @change="load"
        />
      </el-col>
    </el-row>
    <el-row :gutter="12">
      <el-col v-for="item in cards" :key="item.key" :xs="12" :sm="8" :md="4">
        <el-card shadow="never">
          <div class="cs-label">{{ item.label }}</div>
          <div class="cs-value">{{ first()?.[item.key] ?? 0 }}{{ item.suffix }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="cs-row">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="cs-card">
          <template #header><span>会话与转人工趋势</span></template>
          <div v-if="overallRows.length" ref="trendEl" class="chart"></div>
          <el-empty v-else description="暂无趋势数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-card shadow="never" class="cs-card">
          <template #header><span>渠道会话分布</span></template>
          <div v-if="channelRows.length" ref="channelEl" class="chart"></div>
          <el-empty v-else description="暂无渠道数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-card shadow="never" class="cs-card">
          <template #header><span>服务漏斗</span></template>
          <div v-if="funnel.sessions > 0" ref="funnelEl" class="chart"></div>
          <el-empty v-else description="暂无漏斗数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header"><span>日指标</span><el-button type="primary" size="small" :loading="rebuilding" @click="rebuild">重建今日指标</el-button></div>
      </template>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="statDate" label="日期" width="150" />
        <el-table-column label="渠道" width="100">
          <template #default="{ row }">{{ row.channelCode || '全渠道' }}</template>
        </el-table-column>
        <el-table-column prop="botSessions" label="自助会话" width="110" />
        <el-table-column prop="handoffCount" label="转人工" width="100" />
        <el-table-column prop="firstResponseP95Ms" label="首响P95(ms)" width="130" />
        <el-table-column prop="resolveRate" label="解决率(%)" width="120" />
        <el-table-column prop="handoffRate" label="转人工率(%)" width="130" />
        <el-table-column prop="csatAvg" label="CSAT" width="100" />
        <el-table-column prop="csatResponseRate" label="回收率(%)" width="120" />
        <el-table-column prop="slaPassRate" label="SLA达标(%)" width="130" />
        <el-table-column prop="containmentRate" label="AI自动解决(%)" width="130" />
        <el-table-column prop="repeatContactRate" label="重复进线率(%)" width="130" />
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无指标，点击右上角重建" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-filter { margin-bottom: 12px; }
.cs-row { margin-top: 12px; }
.cs-card { margin-top: 12px; }
.cs-row .cs-card { margin-top: 0; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-label { font-size: 12px; color: var(--el-text-color-secondary); }
.cs-value { font-size: 24px; font-weight: 500; margin-top: 6px; }
.chart { height: 260px; }
</style>
