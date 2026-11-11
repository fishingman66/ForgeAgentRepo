<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="工作空间">
          <el-select v-model="queryParam.workspaceId" placeholder="全部空间" clearable filterable style="width: 170px" @change="handleQuery">
            <el-option v-for="ws in workspaceOptions" :key="ws.id" :label="ws.name" :value="String(ws.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属部门">
          <el-tree-select
            v-model="queryParam.deptId"
            :data="deptOptions"
            :props="{ value: 'id', label: 'label', children: 'children' } as any"
            value-key="id"
            placeholder="全部部门"
            check-strictly
            clearable
            style="width: 170px"
            @change="handleQuery"
          />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-input v-model="queryParam.eventType" placeholder="如 apikey.create" clearable style="width: 170px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="queryParam.severity" placeholder="全部" clearable style="width: 110px" @change="handleQuery">
            <el-option label="INFO" value="INFO" />
            <el-option label="WARN" value="WARN" />
            <el-option label="RISK" value="RISK" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="queryParam.result" placeholder="全部" clearable style="width: 110px" @change="handleQuery">
            <el-option label="成功" value="0" />
            <el-option label="失败" value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="queryParam.operatorName" placeholder="操作人" clearable style="width: 140px" @keyup.enter="handleQuery" />
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
            <h3>审计日志</h3>
            <span class="heading-tip">覆盖凭证变更、市场操作、Key 生命周期、工作流/知识库/触发器/空间等敏感事件;日志只增不改</span>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="logList" class="data-table">
        <el-table-column label="时间" prop="createTime" width="170" align="center" />
        <el-table-column label="级别" width="80" align="center">
          <template #default="scope">
            <el-tag size="small" :type="severityTag(scope.row.severity)" effect="dark">{{ scope.row.severity || 'INFO' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="事件类型" width="170">
          <template #default="scope">
            <el-tag size="small" :type="eventTag(scope.row.eventType)" effect="plain">{{ scope.row.eventType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工作空间" min-width="130" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.workspaceName || '平台级' }}</template>
        </el-table-column>
        <el-table-column label="操作人" prop="operatorName" width="110" show-overflow-tooltip />
        <el-table-column label="来源" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" type="info" effect="plain">{{ scope.row.sourceChannel || 'web' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="80" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.result === '1' ? 'danger' : 'success'" effect="plain">
              {{ scope.row.result === '1' ? '失败' : '成功' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="140" show-overflow-tooltip>
          <template #default="scope">
            <span v-if="scope.row.targetType">{{ scope.row.targetType }}#{{ scope.row.targetId }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" prop="detail" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="scope">
            <el-button size="small" text type="primary" icon="View" @click="openDetail(scope.row as AuditLogVO)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 日志详情 -->
    <el-drawer v-model="detail.visible" title="审计详情" size="640px">
      <el-descriptions v-if="detail.data" :column="1" border>
        <el-descriptions-item label="时间">{{ detail.data.createTime }}</el-descriptions-item>
        <el-descriptions-item label="级别">
          <el-tag size="small" :type="severityTag(detail.data.severity)" effect="dark">{{ detail.data.severity || 'INFO' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="事件类型">{{ detail.data.eventType }}</el-descriptions-item>
        <el-descriptions-item label="工作空间">{{ detail.data.workspaceName || '平台级' }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.data.operatorName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.data.sourceChannel || 'web' }}</el-descriptions-item>
        <el-descriptions-item label="客户端IP">{{ detail.data.clientIp || '-' }}</el-descriptions-item>
        <el-descriptions-item label="User-Agent">
          <span class="ua-text">{{ detail.data.userAgent || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="结果">
          <el-tag size="small" :type="detail.data.result === '1' ? 'danger' : 'success'">
            {{ detail.data.result === '1' ? '失败' : '成功' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="链路ID">
          <span class="trace-id">{{ detail.data.traceId || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="对象">
          <span v-if="detail.data.targetType">{{ detail.data.targetType }}#{{ detail.data.targetId }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="明细">
          <pre class="detail-pre">{{ detailDisplay }}</pre>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 变更前后对比 -->
      <template v-if="hasDiff">
        <el-divider content-position="left">变更前后对比</el-divider>
        <div class="diff-container">
          <div class="diff-panel">
            <div class="diff-header">变更前</div>
            <pre class="diff-content">{{ formatJson(detail.data?.oldValue) }}</pre>
          </div>
          <div class="diff-panel">
            <div class="diff-header">变更后</div>
            <pre class="diff-content">{{ formatJson(detail.data?.newValue) }}</pre>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="AgentAudit">
import { listAuditLog } from '@/api/agent/audit';
import type { AuditLogQuery, AuditLogVO } from '@/api/agent/audit/types';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';
import { deptTreeSelect } from '@/api/system/user';
import type { DeptTreeVO } from '@/api/system/dept/types';

const loading = ref(false);
const logList = ref<AuditLogVO[]>([]);
const total = ref(0);
const workspaceOptions = ref<WorkspaceVO[]>([]);
const deptOptions = ref<DeptTreeVO[]>([]);
const dateRange = ref<[string, string] | null>(null);

const queryParam = ref<AuditLogQuery>({
  pageNum: 1,
  pageSize: 10,
  workspaceId: '',
  deptId: undefined,
  eventType: '',
  operatorName: '',
  severity: '',
  result: ''
});

const eventTag = (eventType: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  if (eventType.startsWith('credential.')) return 'warning';
  if (eventType.startsWith('apikey.')) return 'primary';
  if (eventType.startsWith('market.')) return 'success';
  if (eventType.startsWith('agent.')) return 'info';
  if (eventType.startsWith('workflow.')) return 'primary';
  if (eventType.startsWith('kb.')) return 'warning';
  if (eventType.startsWith('trigger.')) return 'success';
  if (eventType.startsWith('workspace.')) return 'danger';
  if (eventType.startsWith('member.')) return 'warning';
  return 'info';
};

const severityTag = (severity?: string): 'success' | 'warning' | 'danger' | 'info' => {
  switch (severity) {
    case 'RISK': return 'danger';
    case 'WARN': return 'warning';
    default: return 'info';
  }
};

const getList = async () => {
  loading.value = true;
  try {
    const params = { ...queryParam.value };
    if (dateRange.value?.[0]) params.beginTime = dateRange.value[0] + ' 00:00:00';
    if (dateRange.value?.[1]) params.endTime = dateRange.value[1] + ' 23:59:59';
    const res = await listAuditLog(params);
    logList.value = res.data?.rows || [];
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
  queryParam.value = {
    pageNum: 1,
    pageSize: 10,
    workspaceId: '',
    deptId: undefined,
    eventType: '',
    operatorName: '',
    severity: '',
    result: ''
  };
  dateRange.value = null;
  handleQuery();
};

// ============ 详情 ============
const detail = reactive<{ visible: boolean; data: AuditLogVO | null }>({ visible: false, data: null });
const detailDisplay = computed(() => {
  if (!detail.data?.detail) return '无';
  try {
    return JSON.stringify(JSON.parse(detail.data.detail), null, 2);
  } catch {
    return detail.data.detail;
  }
});

const hasDiff = computed(() => {
  return !!(detail.data?.oldValue || detail.data?.newValue);
});

const formatJson = (jsonStr?: string) => {
  if (!jsonStr) return '无';
  try {
    return JSON.stringify(JSON.parse(jsonStr), null, 2);
  } catch {
    return jsonStr;
  }
};

const openDetail = (row: AuditLogVO) => {
  detail.data = row;
  detail.visible = true;
};

onMounted(async () => {
  getList();
  const res = await myWorkspaces();
  workspaceOptions.value = res.data || [];
  const deptRes = await deptTreeSelect();
  deptOptions.value = deptRes.data || [];
});
</script>

<style scoped>
.detail-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
}

.ua-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.trace-id {
  font-family: monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.diff-container {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}

.diff-panel {
  flex: 1;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: hidden;
}

.diff-header {
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  font-weight: 600;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color);
}

.diff-content {
  margin: 0;
  padding: 12px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow: auto;
  background: var(--el-bg-color);
}
</style>
