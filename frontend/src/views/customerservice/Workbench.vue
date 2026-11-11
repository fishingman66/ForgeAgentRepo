<script setup lang="ts">
/**
 * 坐席工作台(C1):待办队列 / 抢单 / 回复 / 流转的主操作页。
 * 数据流:listTickets 轮询 + assignQueue 手动分配 + claim/reply/transition 操作;
 * 操作结果通过 SSE 事件(TICKET_EVENT/CHAT)实时同步给访客与其他坐席。
 * 扩展点:语音通话弹屏(CALL_* 事件)、坐席状态切换、会话转接。
 */
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  assignQueue,
  claimTicket,
  getHandoffSummary,
  getTicket,
  listTickets,
  transferTicket,
  transitionTicket
} from '@/api/customerservice';
import type { CsHandoffSummary, CsTicket, CsTicketEvent } from '@/api/customerservice';
import TicketDetail from './TicketDetail.vue';
import ChatPanel from './ChatPanel.vue';

const tickets = ref<CsTicket[]>([]);
const current = ref<CsTicket | null>(null);
const summary = ref<CsHandoffSummary | null>(null);
const events = ref<CsTicketEvent[]>([]);
const statusFilter = ref('');
const loading = ref(false);

const statusOptions = [
  { value: '', label: '全部' },
  { value: 'WAITING_AGENT', label: '待接单' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'ON_HOLD', label: '已挂起' },
  { value: 'WAITING_CONFIRM', label: '待确认' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' }
];

const load = async () => {
  loading.value = true;
  try {
    const res = await listTickets(statusFilter.value || undefined);
    tickets.value = res.data ?? [];
  } catch {
    ElMessage.error('工单加载失败');
  } finally {
    loading.value = false;
  }
};

const open = async (row: CsTicket) => {
  current.value = row;
  try {
    const res = await getTicket(row.id);
    events.value = res.data?.events ?? [];
    if (res.data?.ticket) {
      current.value = res.data.ticket;
    }
  } catch {
    events.value = [];
  }
  // 摘要单独请求:它失败不应该把流转轨迹一起清掉
  try {
    const summ = await getHandoffSummary(row.id);
    summary.value = summ.data ?? null;
  } catch {
    summary.value = null;
  }
};

const act = async (action: string) => {
  if (!current.value) {
    return;
  }
  const id = current.value.id;
  try {
    if (action === 'claim') {
      await claimTicket(id);
    } else {
      await transitionTicket(id, action);
    }
    ElMessage.success('操作成功');
    await load();
    await open({ ...current.value, id } as CsTicket);
  } catch {
    ElMessage.error('操作失败，请检查工单状态');
  }
};

/** 转派(P0):输入目标坐席 userId;留空则退回工单池由 ACD 重新分配。 */
const transfer = async () => {
  if (!current.value) {
    return;
  }
  const id = current.value.id;
  let raw = '';
  try {
    const res = await ElMessageBox.prompt(
      '输入目标坐席的 userId；留空则退回工单池，由 ACD 重新分配',
      '转派工单',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '坐席 userId（可留空）' }
    );
    raw = (res.value ?? '').trim();
  } catch {
    return;
  }
  const assigneeId = raw ? Number(raw) : undefined;
  if (raw && (!Number.isFinite(assigneeId) || (assigneeId as number) <= 0)) {
    ElMessage.warning('坐席 ID 需为正整数');
    return;
  }
  try {
    await transferTicket(id, assigneeId);
    ElMessage.success(assigneeId ? '已转派' : '已退回工单池');
    await load();
    await open({ ...current.value, id } as CsTicket);
  } catch {
    ElMessage.error('转派失败，请检查工单状态与目标坐席');
  }
};

const assign = async () => {
  try {
    await assignQueue();
    ElMessage.success('已执行排队分配');
    await load();
  } catch {
    ElMessage.error('分配失败');
  }
};

const canReply = () => current.value?.status === 'PROCESSING';

onMounted(load);
</script>

<template>
  <div class="cs-workbench">
    <el-row :gutter="16">
      <el-col :span="9">
        <el-card shadow="never">
          <template #header>
            <div class="cs-header">
              <span>工单池</span>
              <el-button size="small" @click="assign">执行分配</el-button>
            </div>
          </template>
          <div class="cs-filter">
            <el-select v-model="statusFilter" size="small" style="width: 100%" @change="load">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </div>
          <el-table v-loading="loading" :data="tickets" size="small" highlight-current-row @row-click="open">
            <el-table-column prop="ticketNo" label="工单号" min-width="140" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="120" />
            <el-table-column prop="priority" label="优先级" width="90" />
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never">
          <template #header>
            <div class="cs-header">
              <span>坐席处理 · {{ current?.ticketNo ?? '未选择工单' }}</span>
              <span v-if="current" class="cs-status">{{ current.status }}</span>
            </div>
          </template>

          <template v-if="current">
            <el-alert
              v-if="summary?.oneLineSummary"
              type="info"
              :closable="false"
              class="cs-summary"
              :title="summary.oneLineSummary"
            >
              <div v-if="summary.userIntent" class="cs-summary-line">意图：{{ summary.userIntent }}</div>
              <div class="cs-summary-line">情绪：{{ summary.emotion }}</div>
              <div v-if="summary.triedSolutions?.length" class="cs-summary-line">
                已尝试：{{ summary.triedSolutions.join('；') }}
              </div>
              <div v-if="summary.pendingIssues?.length" class="cs-summary-line">
                待解决：{{ summary.pendingIssues.join('；') }}
              </div>
              <div v-if="summary.suggestedNextStep" class="cs-summary-line">
                建议下一步：{{ summary.suggestedNextStep }}
              </div>
              <div v-if="summary.suggestedReply" class="cs-summary-line">
                建议开场：{{ summary.suggestedReply }}
              </div>
            </el-alert>

            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item label="会话ID">{{ current.conversationId }}</el-descriptions-item>
              <el-descriptions-item label="分类">{{ current.category }}</el-descriptions-item>
              <el-descriptions-item label="转人工原因">{{ current.handoffReason ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="入队时间">{{ current.waitingSince ?? '-' }}</el-descriptions-item>
            </el-descriptions>

            <div class="cs-actions">
              <el-button v-if="current.status === 'WAITING_AGENT'" type="primary" size="small" @click="act('claim')">接单</el-button>
              <el-button v-if="current.status === 'WAITING_AGENT'" size="small" @click="act('cancel-handoff')">取消转人工</el-button>
              <el-button v-if="current.status === 'PROCESSING'" size="small" @click="act('hold')">挂起</el-button>
              <el-button v-if="current.status === 'ON_HOLD'" size="small" @click="act('resume')">恢复</el-button>
              <el-button v-if="current.status === 'PROCESSING'" size="small" @click="transfer">转派</el-button>
              <el-button v-if="current.status === 'PROCESSING'" type="primary" size="small" @click="act('resolve')">标记已解决</el-button>
              <el-button v-if="current.status === 'WAITING_CONFIRM'" size="small" @click="act('reject')">驳回</el-button>
              <el-button v-if="current.status === 'WAITING_CONFIRM'" size="small" @click="act('close')">关单</el-button>
              <el-button v-if="current.status === 'RESOLVED' || current.status === 'CLOSED'" size="small" @click="act('reopen')">重开</el-button>
            </div>

            <el-divider content-position="left">坐席回复</el-divider>
            <ChatPanel
              :ticket-id="current.id"
              :conversation-id="current.conversationId"
              :disabled="!canReply()"
              @replied="open(current)"
            />

            <el-divider content-position="left">流转轨迹</el-divider>
            <TicketDetail :events="events" />
          </template>
          <el-empty v-else description="从左侧选择一张工单" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.cs-workbench {
  padding: 12px;
}
.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cs-filter {
  margin-bottom: 10px;
}
.cs-status {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-actions {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.cs-summary {
  margin-bottom: 10px;
}
.cs-summary-line {
  font-size: 12px;
  line-height: 1.7;
}
</style>
