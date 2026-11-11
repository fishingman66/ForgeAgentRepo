<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="Agent">
          <el-select v-model="queryParam.agentId" placeholder="全部 Agent" clearable filterable style="width: 200px" @change="handleQuery">
            <el-option v-for="a in agentOptions" :key="a.id" :label="a.name" :value="String(a.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="反馈类型">
          <el-select v-model="queryParam.feedback" placeholder="全部" clearable style="width: 120px" @change="handleQuery">
            <el-option label="点赞" value="like" />
            <el-option label="点踩" value="dislike" />
          </el-select>
        </el-form-item>
        <el-form-item label="反馈时间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="-"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 240px"
            @change="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel" style="margin-top: 10px">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>消息反馈</h3>
            <span class="heading-tip">聚合用户对助手回复的点赞/点踩记录,含标签与备注,可用于定位低质回答并回流示例库</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:feedback:export']" type="primary" plain icon="Download" :loading="exporting" @click="handleExport">导出</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="rows" class="data-table">
        <el-table-column label="反馈时间" prop="feedbackTime" width="170" align="center" />
        <el-table-column label="Agent" min-width="140" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.agentName || '-' }}</template>
        </el-table-column>
        <el-table-column label="反馈人" prop="userName" width="120" show-overflow-tooltip />
        <el-table-column label="类型" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.feedback === 'like' ? 'success' : 'danger'" effect="plain">
              {{ scope.row.feedback === 'like' ? '点赞' : '点踩' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="160">
          <template #default="scope">
            <template v-if="scope.row.feedbackTags">
              <el-tag v-for="t in scope.row.feedbackTags.split(',').filter(Boolean)" :key="t" size="small" type="warning" effect="plain" style="margin-right: 4px">
                {{ t }}
              </el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="feedbackComment" min-width="200" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.feedbackComment || '-' }}</template>
        </el-table-column>
        <el-table-column label="消息快照" prop="msgSnapshot" min-width="260" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.msgSnapshot || '-' }}</template>
        </el-table-column>
        <el-table-column label="消息时间" prop="msgTime" width="170" align="center" />
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="AgentFeedback">
import { listFeedback, exportFeedback } from '@/api/agent/feedback';
import type { FeedbackQuery, FeedbackVO } from '@/api/agent/feedback/types';
import { listPublishedAgent } from '@/api/agent/agent';
import type { AgentVO } from '@/api/agent/agent/types';
import { saveBlob } from '@/utils/save';

const loading = ref(false);
const exporting = ref(false);
const rows = ref<FeedbackVO[]>([]);
const total = ref(0);
const agentOptions = ref<AgentVO[]>([]);
const dateRange = ref<[string, string] | null>(null);

const queryParam = ref<FeedbackQuery>({
  pageNum: 1,
  pageSize: 10,
  agentId: '',
  feedback: ''
});

const buildParams = () => {
  const params: FeedbackQuery = { ...queryParam.value };
  if (dateRange.value?.[0]) params.beginTime = dateRange.value[0] + ' 00:00:00';
  if (dateRange.value?.[1]) params.endTime = dateRange.value[1] + ' 23:59:59';
  return params;
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listFeedback(buildParams());
    rows.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParam.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParam.value = { pageNum: 1, pageSize: 10, agentId: '', feedback: '' };
  dateRange.value = null;
  handleQuery();
};

const handleExport = async () => {
  exporting.value = true;
  try {
    // 响应拦截器对 responseType=blob 直接返回 Blob 本体
    const blob = (await exportFeedback(buildParams())) as unknown as Blob;
    saveBlob(blob, `消息反馈_${new Date().getTime()}.xlsx`);
  } finally {
    exporting.value = false;
  }
};

onMounted(async () => {
  getList();
  const res = await listPublishedAgent();
  agentOptions.value = res.data || [];
});
</script>
