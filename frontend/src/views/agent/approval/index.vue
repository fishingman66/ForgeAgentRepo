<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="状态">
          <el-select v-model="queryParam.status" placeholder="全部" clearable style="width: 150px">
            <el-option v-for="(v, k) in statusMap" :key="k" :label="v.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="queryParam.toolSource" placeholder="全部" clearable style="width: 150px">
            <el-option label="MCP" value="mcp" />
            <el-option label="平台工具" value="tool" />
            <el-option label="工作流" value="workflow" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="工具名称/MCP 服务器" clearable style="width: 200px" @keyup.enter="handleQuery" />
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
            <h3>工具审批中心</h3>
            <span class="heading-tip">Agent 以 ask 模式调用工具或工作流执行到审批节点时产生待审批单;工作流审批超时将自动关闭并终止工作流</span>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="approvalList" class="data-table">
        <el-table-column label="ID" prop="id" width="80" align="center" />
        <el-table-column label="工具名称" prop="toolName" min-width="120" show-overflow-tooltip />
        <el-table-column label="来源" min-width="150" show-overflow-tooltip>
          <template #default="scope">
            <el-tag size="small" :type="sourceMeta(scope.row.toolSource).type" effect="plain" style="margin-right: 6px">
              {{ sourceMeta(scope.row.toolSource).label }}
            </el-tag>
            <span v-if="scope.row.serverName" style="color: var(--el-text-color-secondary)">{{ scope.row.serverName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="调用参数" min-width="200">
          <template #default="scope">
            <el-tooltip :content="scope.row.callArgs || '-'" placement="top" :show-after="300">
              <span class="call-args">{{ scope.row.callArgs || '-' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="statusMap[scope.row.status]?.type || 'info'">
              {{ statusMap[scope.row.status]?.label || scope.row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批说明" prop="resultNote" min-width="140" show-overflow-tooltip>
          <template #default="scope">
            <span v-if="scope.row.resultNote">{{ scope.row.resultNote }}</span>
            <span v-else style="color: var(--el-text-color-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" prop="createTime" width="170" align="center" />
        <el-table-column label="截止时间" width="170" align="center">
          <template #default="scope">
            <span v-if="scope.row.expiredAt && scope.row.status === 'pending'"
              :style="{ color: new Date(scope.row.expiredAt).getTime() < Date.now() ? 'var(--el-color-danger)' : 'var(--el-text-color-regular)' }">
              {{ scope.row.expiredAt }}
            </span>
            <span v-else style="color: var(--el-text-color-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="审批时间" prop="approveTime" width="170" align="center" />
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="scope">
            <template v-if="scope.row.status === 'pending'">
              <el-button v-hasPermi="['agent:approval:handle']" size="small" text type="success" icon="Check" @click="handleApprove(scope.row as ApprovalVO)">通过</el-button>
              <el-button v-hasPermi="['agent:approval:handle']" size="small" text type="danger" icon="Close" @click="handleReject(scope.row as ApprovalVO)">拒绝</el-button>
            </template>
            <span v-else style="color: var(--el-text-color-secondary)">已处理</span>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="AgentApproval">
import { approveApproval, listApproval, rejectApproval } from '@/api/agent/approval';
import type { ApprovalQuery, ApprovalVO } from '@/api/agent/approval/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const approvalList = ref<ApprovalVO[]>([]);
const total = ref(0);

const queryParam = ref<ApprovalQuery>({ pageNum: 1, pageSize: 10, status: 'pending', keyword: '', toolSource: '' });

const statusMap: Record<string, { label: string; type: 'warning' | 'success' | 'danger' | 'info' }> = {
  pending: { label: '待审批', type: 'warning' },
  approved: { label: '已通过', type: 'success' },
  rejected: { label: '已拒绝', type: 'danger' },
  expired: { label: '已超时', type: 'info' },
  cancelled: { label: '已取消', type: 'info' }
};

/** 审批单来源映射:工具/MCP/工作流节点 */
const sourceMeta = (source: string): { label: string; type: 'success' | 'primary' | 'warning' } => {
  const map: Record<string, { label: string; type: 'success' | 'primary' | 'warning' }> = {
    mcp: { label: 'MCP', type: 'success' },
    tool: { label: '平台工具', type: 'primary' },
    workflow: { label: '工作流', type: 'warning' }
  };
  return map[source] || { label: source || '平台工具', type: 'primary' };
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listApproval(queryParam.value);
    approvalList.value = res.data?.rows || [];
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
  queryParam.value = { pageNum: 1, pageSize: 10, status: 'pending', keyword: '' };
  handleQuery();
};

const handleApprove = async (row: ApprovalVO) => {
  try {
    const { value } = await ElMessageBox.prompt(`确认通过 Agent 对工具「${row.toolName}」的调用吗?可填写审批说明(可选)`, '审批通过', {
      confirmButtonText: '通过',
      cancelButtonText: '取消',
      inputPlaceholder: '审批说明(可选)',
      inputValidator: (v: string) => !v || v.length <= 200 || '说明不能超过 200 字符'
    });
    await approveApproval(row.id, value || undefined);
    proxy?.$modal.msgSuccess('已通过,Agent 将继续执行该工具调用');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const handleReject = async (row: ApprovalVO) => {
  try {
    const { value } = await ElMessageBox.prompt(`确认拒绝 Agent 对工具「${row.toolName}」的调用吗?拒绝原因会反馈给 Agent`, '审批拒绝', {
      confirmButtonText: '拒绝',
      cancelButtonText: '取消',
      type: 'warning',
      inputPlaceholder: '拒绝原因(可选)',
      inputValidator: (v: string) => !v || v.length <= 200 || '原因不能超过 200 字符'
    });
    await rejectApproval(row.id, value || undefined);
    proxy?.$modal.msgSuccess('已拒绝');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

onMounted(() => {
  getList();
});
</script>

<style scoped>
.call-args {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  font-family: var(--el-font-family, monospace);
  font-size: 12px;
}
</style>
