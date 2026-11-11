<template>
  <div class="p-2 app-container">
    <!-- 精简标题栏 -->
    <div class="dashboard-head">
      <div class="head-left">
        <el-tag :type="isPlatform ? 'danger' : 'primary'" effect="plain" size="large">
          {{ isPlatform ? '平台视角' : '空间视角' }}
        </el-tag>
        <span class="head-desc">{{ isPlatform ? '全平台运营总览' : '您管理的工作空间运营总览' }}</span>
      </div>
      <div class="head-right">
        <span class="head-tip">数据截止 {{ overview?.dataEndTime || '-' }}</span>
        <el-button icon="Refresh" :loading="loading" @click="loadOverview">刷新</el-button>
      </div>
    </div>

    <el-skeleton v-if="loading && !overview" animated :rows="8" />

    <template v-else>
      <!-- KPI 指标卡 -->
      <el-row :gutter="12">
        <el-col v-for="item in kpiItems" :key="item.label" :xs="12" :sm="8" :md="4">
          <div class="metric-card" :class="[`tone-${item.tone}`, { focal: item.focal }]">
            <div class="metric-icon">
              <el-icon :size="18"><component :is="item.icon" /></el-icon>
            </div>
            <div class="metric-body">
              <div class="metric-value">{{ displayMap[item.label] ?? '-' }}</div>
              <div class="metric-label">{{ item.label }}</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 趋势与排行 -->
      <el-row :gutter="12">
        <el-col :xs="24" :md="16">
          <el-card shadow="hover" class="fill-card">
            <template #header><span>近 14 天 Token 趋势</span></template>
            <div v-if="trend.length" ref="trendRef" style="height: 300px"></div>
            <el-empty v-else description="暂无用量数据" :image-size="70" />
          </el-card>
        </el-col>
        <el-col :xs="24" :md="8">
          <el-card shadow="hover" class="fill-card">
            <template #header><span>Agent 用量排行 (近 14 天)</span></template>
            <div v-if="overview?.agentRank?.length" class="rank-list">
              <div v-for="(r, i) in overview.agentRank" :key="i" class="rank-row">
                <span class="rank-idx" :class="{ 'rank-top': i === 0 }">{{ i + 1 }}</span>
                <span class="rank-name" :title="r.name">{{ r.name }}</span>
                <span class="rank-val">{{ formatNum(r.totalTokens) }}</span>
                <span class="rank-pct">{{ r.ratio != null ? r.ratio.toFixed(0) + '%' : '' }}</span>
                <i class="rank-bar" :style="{ width: barWidth(r.ratio) }"></i>
              </div>
            </div>
            <el-empty v-else description="暂无数据" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>

      <!-- 满意度 / 状态分布 / 待办 -->
      <el-row :gutter="12" style="margin-top: 12px">
        <el-col :xs="24" :md="8">
          <el-card shadow="hover" class="mini-card">
            <template #header><span>消息反馈满意度</span></template>
            <div v-if="f?.satisfaction != null" class="feedback-wrap">
              <div ref="feedbackRef" class="donut"></div>
              <div class="feedback-side">
                <div class="fb-item">
                  <span class="fb-num like">{{ formatNum(f?.likeCount) }}</span>
                  <span class="fb-label">累计点赞 · 今日 +{{ f?.likeCountToday ?? 0 }}</span>
                </div>
                <div class="fb-item">
                  <span class="fb-num dislike">{{ formatNum(f?.dislikeCount) }}</span>
                  <span class="fb-label">累计点踩 · 今日 +{{ f?.dislikeCountToday ?? 0 }}</span>
                </div>
              </div>
            </div>
            <el-alert
              v-else
              type="info"
              :closable="false"
              show-icon
              title="暂无反馈数据:用户在对话页对助手消息点赞/点踩后,此处将聚合展示满意度"
            />
          </el-card>
        </el-col>
        <el-col :xs="24" :md="8">
          <el-card shadow="hover" class="mini-card">
            <template #header><span>Agent 状态分布</span></template>
            <div v-if="agentTotal > 0" ref="statusRef" class="donut"></div>
            <el-empty v-else description="暂无 Agent" :image-size="60" />
          </el-card>
        </el-col>
        <el-col :xs="24" :md="8">
          <el-card shadow="hover" class="mini-card">
            <template #header><span>待办事项</span></template>
            <div v-if="todoItems.length" class="todo-list">
              <div v-for="t in todoItems" :key="t.type" class="todo-row" @click="goto(t.path)">
                <span class="todo-icon" :class="{ 'todo-icon-active': t.count > 0 }">
                  <el-icon :size="15"><component :is="t.icon" /></el-icon>
                </span>
                <span class="todo-label">{{ t.label }}</span>
                <span class="todo-count" :class="{ 'todo-count-active': t.count > 0 }">{{ t.count }}</span>
              </div>
            </div>
            <el-empty v-else description="暂无待办事项" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup lang="ts" name="AgentDashboard">
import * as echarts from 'echarts';
import { ElMessage } from 'element-plus';
import {
  OfficeBuilding,
  User,
  Cpu,
  CircleCheck,
  EditPen,
  CircleClose,
  ChatDotRound,
  Message,
  Lightning,
  Pointer,
  Shop,
  Stamp,
  Warning,
  AlarmClock
} from '@element-plus/icons-vue';
import { dashboardOverview } from '@/api/agent/dashboard';
import type { DashboardOverviewVO, DashboardTrendPoint } from '@/api/agent/dashboard/types';
import { useRouter } from 'vue-router';
import type { Component } from 'vue';

const router = useRouter();
const overview = ref<DashboardOverviewVO | null>(null);
const loading = ref(false);
const trendRef = ref<HTMLElement>();
const feedbackRef = ref<HTMLElement>();
const statusRef = ref<HTMLElement>();
let trendChart: echarts.ECharts | undefined;
let feedbackChart: echarts.ECharts | undefined;
let statusChart: echarts.ECharts | undefined;

/** 容器被 v-if 重建后旧实例绑定的是已分离节点:检测并重新初始化 */
const ensureChart = (chart: echarts.ECharts | undefined, el: HTMLElement): echarts.ECharts => {
  if (chart && chart.getDom() !== el) {
    chart.dispose();
    chart = undefined;
  }
  return chart || echarts.init(el);
};

const isPlatform = computed(() => overview.value?.scope === 'platform');
const trend = computed<DashboardTrendPoint[]>(() => overview.value?.trend || []);
const f = computed(() => overview.value?.feedback);
const agentTotal = computed(() => overview.value?.kpi?.agentCount ?? 0);

const formatNum = (n?: number | null) => (n == null ? '-' : n.toLocaleString());

// ---------- KPI 配置与数字滚动 ----------
interface KpiItem {
  label: string;
  value?: number | null;
  icon: Component;
  tone: 'primary' | 'success' | 'warning' | 'info';
  focal?: boolean;
}

const kpiItems = computed<KpiItem[]>(() => {
  const k = overview.value?.kpi;
  const items: KpiItem[] = [];
  if (isPlatform.value) {
    items.push({ label: '工作空间', value: k?.workspaceCount, icon: OfficeBuilding, tone: 'primary' });
  }
  items.push(
    { label: '成员', value: k?.memberCount, icon: User, tone: 'primary' },
    { label: 'Agent 总数', value: k?.agentCount, icon: Cpu, tone: 'primary' },
    { label: '已发布', value: k?.publishedCount, icon: CircleCheck, tone: 'success' },
    { label: '草稿', value: k?.draftCount, icon: EditPen, tone: 'warning' },
    { label: '已停用', value: k?.disabledCount, icon: CircleClose, tone: 'info' },
    { label: '会话', value: k?.sessionCount, icon: ChatDotRound, tone: 'primary' },
    { label: '消息', value: k?.messageCount, icon: Message, tone: 'primary' },
    { label: '累计 Token', value: k?.totalTokens, icon: Lightning, tone: 'primary', focal: true },
    { label: '调用次数', value: k?.callCount, icon: Pointer, tone: 'primary' }
  );
  return items;
});

const displayMap = ref<Record<string, string>>({});

const animateValue = (key: string, target?: number | null) => {
  if (target == null) {
    displayMap.value[key] = '-';
    return;
  }
  const duration = 600;
  const t0 = performance.now();
  const step = (t: number) => {
    const p = Math.min(1, (t - t0) / duration);
    const eased = 1 - Math.pow(1 - p, 3);
    displayMap.value[key] = Math.round(target * eased).toLocaleString();
    if (p < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
};

// ---------- 排行榜 ----------
const maxRatio = computed(() => Math.max(0, ...(overview.value?.agentRank || []).map((r) => r.ratio || 0)));
const barWidth = (ratio?: number | null) => `${maxRatio.value > 0 && ratio ? (ratio / maxRatio.value) * 100 : 0}%`;

// ---------- 待办 ----------
interface TodoItem {
  type: string;
  label: string;
  count: number;
  path: string;
  icon: Component;
}

const todoItems = computed<TodoItem[]>(() => {
  const t = overview.value?.todo;
  if (!t) return [];
  const list: TodoItem[] = [];
  if (isPlatform.value && t.reviewPendingCount != null) {
    list.push({ type: 'review', label: '待审核市场条目', count: t.reviewPendingCount, path: '/agent/market-review', icon: Shop });
  }
  if (t.approvalPendingCount != null) {
    list.push({ type: 'approval', label: '待处理工具审批', count: t.approvalPendingCount, path: '/agent/approval', icon: Stamp });
  }
  if (t.workflowFailCount != null) {
    list.push({ type: 'workflow', label: '工作流失败运行', count: t.workflowFailCount, path: '/agent/agent', icon: Warning });
  }
  if (t.triggerFailCount != null) {
    list.push({ type: 'trigger', label: '触发器失败记录', count: t.triggerFailCount, path: '/agent/trigger', icon: AlarmClock });
  }
  return list;
});

// ---------- 图表 ----------
const cssVar = (name: string, fallback: string) => {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
};

const hexA = (hex: string, alpha: number) => {
  const m = hex.replace('#', '');
  if (m.length !== 6) return hex;
  const r = parseInt(m.slice(0, 2), 16);
  const g = parseInt(m.slice(2, 4), 16);
  const b = parseInt(m.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${alpha})`;
};

const palette = () => ({
  primary: cssVar('--el-color-primary', '#409EFF'),
  success: cssVar('--el-color-success', '#67C23A'),
  warning: cssVar('--el-color-warning', '#E6A23C'),
  danger: cssVar('--el-color-danger', '#F56C6C'),
  info: cssVar('--el-color-info', '#909399')
});

const renderTrend = () => {
  if (!trendRef.value || !trend.value.length) return;
  trendChart = ensureChart(trendChart, trendRef.value);
  const c = palette();
  const items = trend.value;
  trendChart.setOption({
    color: [c.primary, c.success],
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => Number(v).toLocaleString() },
    legend: { data: ['总 Token', '调用次数'], top: 0 },
    grid: { left: 10, right: 16, top: 40, bottom: 10, containLabel: true },
    xAxis: {
      type: 'category',
      data: items.map((i) => i.statDate),
      axisLabel: {
        interval: 0,
        rotate: 30,
        formatter: (val: string) => val.slice(5)
      }
    },
    yAxis: [
      { type: 'value', name: 'Token', axisLabel: { formatter: (v: number) => (v >= 10000 ? `${(v / 10000).toFixed(v % 10000 === 0 ? 0 : 1)}w` : v) } },
      { type: 'value', name: '次数' }
    ],
    series: [
      {
        name: '总 Token',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: hexA(c.primary, 0.25) },
            { offset: 1, color: hexA(c.primary, 0.02) }
          ])
        },
        data: items.map((i) => i.totalTokens)
      },
      {
        name: '调用次数',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 18,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: items.map((i) => i.callCount)
      }
    ]
  });
};

const renderFeedback = () => {
  if (!feedbackRef.value || f.value?.satisfaction == null) return;
  feedbackChart = ensureChart(feedbackChart, feedbackRef.value);
  const c = palette();
  feedbackChart.setOption({
    color: [c.success, c.danger],
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    title: {
      text: `${f.value.satisfaction}%`,
      subtext: '满意度',
      left: 'center',
      top: '34%',
      itemGap: 4,
      textStyle: { fontSize: 22, fontWeight: 700 },
      subtextStyle: { fontSize: 12 }
    },
    series: [
      {
        type: 'pie',
        radius: ['58%', '78%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        label: { show: false },
        data: [
          { value: f.value.likeCount ?? 0, name: '点赞' },
          { value: f.value.dislikeCount ?? 0, name: '点踩' }
        ]
      }
    ]
  });
};

const renderStatus = () => {
  if (!statusRef.value || agentTotal.value <= 0) return;
  statusChart = ensureChart(statusChart, statusRef.value);
  const c = palette();
  const k = overview.value?.kpi;
  statusChart.setOption({
    color: [c.success, c.warning, c.info],
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10 },
    title: {
      text: formatNum(k?.agentCount),
      subtext: 'Agent 总数',
      left: 'center',
      top: '32%',
      itemGap: 4,
      textStyle: { fontSize: 22, fontWeight: 700 },
      subtextStyle: { fontSize: 12 }
    },
    series: [
      {
        type: 'pie',
        radius: ['52%', '72%'],
        center: ['50%', '42%'],
        avoidLabelOverlap: true,
        label: { show: false },
        data: [
          { value: k?.publishedCount ?? 0, name: '已发布' },
          { value: k?.draftCount ?? 0, name: '草稿' },
          { value: k?.disabledCount ?? 0, name: '已停用' }
        ]
      }
    ]
  });
};

const loadOverview = async () => {
  loading.value = true;
  try {
    const res = await dashboardOverview();
    overview.value = res.data || null;
    kpiItems.value.forEach((i) => animateValue(i.label, i.value));
    await nextTick();
    renderTrend();
    renderFeedback();
    renderStatus();
  } catch (e: any) {
    ElMessage.error(e?.message || '运营看板加载失败');
  } finally {
    loading.value = false;
  }
};

const goto = (path: string) => {
  router.push(path);
};

const onResize = () => {
  trendChart?.resize();
  feedbackChart?.resize();
  statusChart?.resize();
};

onMounted(() => {
  loadOverview();
  window.addEventListener('resize', onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
  trendChart?.dispose();
  feedbackChart?.dispose();
  statusChart?.dispose();
});
</script>

<style scoped>
.dashboard-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px;
  margin-bottom: 12px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.head-desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.head-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* KPI 图标卡 */
.metric-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
  transition: transform 0.2s, box-shadow 0.2s, border-color 0.2s;
}
.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--el-box-shadow-light);
}
.metric-card.focal {
  border-color: var(--el-color-primary);
}
.metric-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.tone-primary .metric-icon {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.tone-success .metric-icon {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.tone-warning .metric-icon {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.tone-info .metric-icon {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}
.metric-body {
  min-width: 0;
}
.metric-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
  white-space: nowrap;
}

.fill-card {
  height: 100%;
}

/* 排行 */
.rank-list {
  display: flex;
  flex-direction: column;
}
.rank-row {
  position: relative;
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto 36px;
  align-items: center;
  gap: 8px;
  padding: 8px 0 10px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.rank-row:last-child {
  border-bottom: none;
}
.rank-idx {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.rank-idx.rank-top {
  background: var(--el-color-primary);
  color: #fff;
}
.rank-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}
.rank-val {
  text-align: right;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.rank-pct {
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.rank-bar {
  position: absolute;
  left: 0;
  bottom: 3px;
  height: 3px;
  border-radius: 2px;
  background: var(--el-color-primary);
  opacity: 0.85;
}

/* 满意度 / 状态分布 */
.mini-card {
  height: 100%;
}
.donut {
  height: 190px;
}
.feedback-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}
.feedback-wrap .donut {
  flex: 1;
  min-width: 0;
}
.feedback-side {
  width: 132px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.fb-item {
  display: flex;
  flex-direction: column;
}
.fb-num {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.fb-num.like {
  color: var(--el-color-success);
}
.fb-num.dislike {
  color: var(--el-color-danger);
}
.fb-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

/* 待办 */
.todo-list {
  display: flex;
  flex-direction: column;
}
.todo-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}
.todo-row:hover {
  background: var(--el-fill-color-light);
}
.todo-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.todo-icon-active {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.todo-label {
  flex: 1;
  font-size: 13px;
}
.todo-count {
  font-size: 16px;
  font-weight: 700;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.todo-count-active {
  color: var(--el-color-danger);
}
</style>
