<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="工作空间">
          <el-select v-model="filter.workspaceId" placeholder="全部空间" clearable filterable style="width: 170px" @change="refreshAll">
            <el-option v-for="ws in workspaceOptions" :key="ws.id" :label="ws.name" :value="String(ws.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属部门">
          <el-tree-select
            v-model="filter.deptId"
            :data="deptOptions"
            :props="{ value: 'id', label: 'label', children: 'children' } as any"
            value-key="id"
            placeholder="全部部门"
            check-strictly
            clearable
            style="width: 170px"
            @change="refreshAll"
          />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="-"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 240px"
            @change="refreshAll"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="refreshAll">查询</el-button>
          <el-button icon="Refresh" @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 指标卡 -->
    <el-row :gutter="12" style="margin-top: 10px">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">总 Token</div>
          <div class="metric-value">{{ formatNum(report?.totalTokens) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">输入 Token</div>
          <div class="metric-value">{{ formatNum(report?.promptTokens) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">输出 Token</div>
          <div class="metric-value">{{ formatNum(report?.completionTokens) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">调用次数</div>
          <div class="metric-value">{{ formatNum(report?.callCount) }}</div>
          <div class="metric-tip">
            估算占比 {{ report?.estimatedRatio != null ? (report.estimatedRatio * 100).toFixed(1) + '%' : '-' }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 趋势与排行 -->
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :xs="24" :md="12">
        <el-card shadow="hover">
          <template #header><span>每日 Token 趋势</span></template>
          <div ref="trendRef" style="height: 300px"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-card shadow="hover">
          <template #header><span>Agent 用量排行</span></template>
          <el-table :data="report?.agentRank || []" size="small" :show-header="false">
            <el-table-column type="index" width="36" />
            <el-table-column prop="name" min-width="110" show-overflow-tooltip />
            <el-table-column width="90" align="right">
              <template #default="scope">{{ formatNum(scope.row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column width="60" align="right">
              <template #default="scope">{{ scope.row.ratio != null ? (scope.row.ratio * 100).toFixed(0) + '%' : '' }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!report?.agentRank?.length" description="暂无数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-card shadow="hover">
          <template #header><span>模型用量排行</span></template>
          <el-table :data="report?.modelRank || []" size="small" :show-header="false">
            <el-table-column type="index" width="36" />
            <el-table-column prop="name" min-width="110" show-overflow-tooltip />
            <el-table-column width="90" align="right">
              <template #default="scope">{{ formatNum(scope.row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column width="60" align="right">
              <template #default="scope">{{ scope.row.ratio != null ? (scope.row.ratio * 100).toFixed(0) + '%' : '' }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!report?.modelRank?.length" description="暂无数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 明细 -->
    <el-card shadow="hover" style="margin-top: 12px">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>调用明细</h3>
            <span class="heading-tip">按会话归因;「估算」标记表示模型未返回精确 Token 数</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:usage:export']" type="primary" plain icon="Download" @click="handleExport">导出</el-button>
          </div>
        </div>
      </template>

      <el-form :inline="true" class="query-form" style="margin-bottom: 8px">
        <el-form-item label="来源">
          <el-select v-model="listQuery.source" placeholder="全部" clearable style="width: 110px" @change="handleListQuery">
            <el-option label="聊天" value="chat" />
            <el-option label="API Key" value="apikey" />
          </el-select>
        </el-form-item>
        <el-form-item label="会话ID">
          <el-input v-model="listQuery.sessionId" placeholder="会话 ID" clearable style="width: 160px" @keyup.enter="handleListQuery" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleListQuery">搜索</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="listLoading" border :data="usageList" class="data-table" size="small">
        <el-table-column label="调用时间" prop="callTime" width="165" align="center" />
        <el-table-column label="Agent" prop="agentName" min-width="120" show-overflow-tooltip />
        <el-table-column label="模型" prop="modelName" min-width="110" show-overflow-tooltip />
        <el-table-column label="会话" prop="sessionId" width="100" align="center" show-overflow-tooltip />
        <el-table-column label="用户" prop="userName" width="100" show-overflow-tooltip />
        <el-table-column label="来源" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.source === 'apikey' ? 'warning' : 'primary'" effect="plain">
              {{ scope.row.source === 'apikey' ? 'API Key' : '聊天' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="输入" prop="promptTokens" width="90" align="right" />
        <el-table-column label="输出" prop="completionTokens" width="90" align="right" />
        <el-table-column label="总计" prop="totalTokens" width="90" align="right" />
        <el-table-column label="估算" width="70" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.estimated === '1'" type="info" size="small">估算</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="70" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.success === '0' ? 'success' : 'danger'" size="small">
              {{ scope.row.success === '0' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="listQuery.pageNum" v-model:limit="listQuery.pageSize" :total="listTotal" @pagination="getList" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="AgentUsage">
import * as echarts from 'echarts';
import { listUsage, usageReport } from '@/api/agent/usage';
import type { TokenUsageVO, UsageQuery, UsageReportVO } from '@/api/agent/usage/types';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';
import { deptTreeSelect } from '@/api/system/user';
import type { DeptTreeVO } from '@/api/system/dept/types';
import { download as requestDownload } from '@/utils/request';

const workspaceOptions = ref<WorkspaceVO[]>([]);
const deptOptions = ref<DeptTreeVO[]>([]);
const dateRange = ref<[string, string] | null>(null);
const filter = reactive({ workspaceId: '', deptId: undefined as string | number | undefined });

const report = ref<UsageReportVO | null>(null);
const listLoading = ref(false);
const usageList = ref<TokenUsageVO[]>([]);
const listTotal = ref(0);
const listQuery = ref<UsageQuery>({ pageNum: 1, pageSize: 10, source: '', sessionId: '' });

const trendRef = ref<HTMLElement>();
let trendChart: echarts.ECharts | undefined;

const formatNum = (n?: number | null) => (n == null ? '-' : n.toLocaleString());

const buildParams = () => {
  const params: UsageQuery = { pageNum: 1, pageSize: 10 };
  if (filter.workspaceId) params.workspaceId = filter.workspaceId;
  if (filter.deptId) params.deptId = filter.deptId;
  if (dateRange.value?.[0]) params.beginTime = dateRange.value[0] + ' 00:00:00';
  if (dateRange.value?.[1]) params.endTime = dateRange.value[1] + ' 23:59:59';
  return params;
};

const loadReport = async () => {
  const res = await usageReport(buildParams());
  report.value = res.data || null;
  await nextTick();
  renderTrend();
};

const renderTrend = () => {
  if (!trendRef.value) return;
  if (!trendChart) trendChart = echarts.init(trendRef.value, 'macarons');
  const items = report.value?.dailyTrend || [];
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['总 Token', '调用次数'] },
    grid: { left: 60, right: 50, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: items.map((i) => i.statDate) },
    yAxis: [
      { type: 'value', name: 'Token' },
      { type: 'value', name: '次数' }
    ],
    series: [
      { name: '总 Token', type: 'line', smooth: true, areaStyle: {}, data: items.map((i) => i.totalTokens) },
      { name: '调用次数', type: 'bar', yAxisIndex: 1, data: items.map((i) => i.callCount) }
    ]
  });
};

const getList = async () => {
  listLoading.value = true;
  try {
    const res = await listUsage({ ...listQuery.value, ...buildParams() });
    usageList.value = res.data?.rows || [];
    listTotal.value = res.data?.total || 0;
  } finally {
    listLoading.value = false;
  }
};

const handleListQuery = () => {
  listQuery.value.pageNum = 1;
  getList();
};

const refreshAll = () => {
  loadReport();
  handleListQuery();
};

const resetFilter = () => {
  filter.workspaceId = '';
  filter.deptId = undefined;
  dateRange.value = null;
  listQuery.value = { pageNum: 1, pageSize: 10, source: '', sessionId: '' };
  refreshAll();
};

const handleExport = () => {
  requestDownload('agent/usage/export', { ...listQuery.value, ...buildParams() }, `token_usage_${new Date().getTime()}.xlsx`);
};

const onResize = () => trendChart?.resize();

onMounted(async () => {
  refreshAll();
  window.addEventListener('resize', onResize);
  const res = await myWorkspaces();
  workspaceOptions.value = res.data || [];
  const deptRes = await deptTreeSelect();
  deptOptions.value = deptRes.data || [];
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
  trendChart?.dispose();
});
</script>

<style scoped>
.metric-card {
  text-align: center;
}
.metric-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.metric-value {
  font-size: 26px;
  font-weight: 700;
  margin-top: 6px;
}
.metric-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}
</style>
