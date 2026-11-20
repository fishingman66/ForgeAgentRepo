<script setup lang="ts">
/**
 * 坐席工作台(C1):待办队列 / 抢单 / 回复 / 流转的主操作页。
 * 数据流:listTickets 轮询 + assignQueue 手动分配 + claim/reply/transition 操作;
 * 操作结果通过 SSE 事件(TICKET_EVENT/CHAT)实时同步给访客与其他坐席。
 * 扩展点:语音通话弹屏(CALL_* 事件)、坐席状态切换、会话转接。
 */
import { onMounted, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus';
import {
  assignQueue,
  claimTicket,
  confirmInvestigation,
  getHandoffSummary,
  getInvestigation,
  getTicket,
  investigateTicket,
  listInvestigations,
  listMentions,
  listTickets,
  transferTicket,
  transitionTicket
} from '@/api/customerservice';
import type { CsHandoffSummary, CsInvestigation, CsTicket, CsTicketEvent } from '@/api/customerservice';
import { listDatasource } from '@/api/agent/datasource';
import TicketDetail from './TicketDetail.vue';
import ChatPanel from './ChatPanel.vue';

const tickets = ref<CsTicket[]>([]);
const current = ref<CsTicket | null>(null);
const summary = ref<CsHandoffSummary | null>(null);
const events = ref<CsTicketEvent[]>([]);
const statusFilter = ref('');
const loading = ref(false);

/* A3 @提及提醒:游标增量拉取,只提示新事件;失败下一轮重试,不推进游标。 */
let mentionCursor = 0;
let mentionPrimed = false;
let mentionTimer: ReturnType<typeof setInterval> | null = null;
const pollMentions = async () => {
  try {
    const res = await listMentions(mentionCursor);
    const rows = res.data ?? [];
    if (!mentionPrimed) {
      // 首次只对齐游标,不把历史提及一次性弹出来
      mentionPrimed = true;
      for (const m of rows) {
        mentionCursor = Math.max(mentionCursor, m.id);
      }
      return;
    }
    for (const m of rows) {
      mentionCursor = Math.max(mentionCursor, m.id);
      ElNotification({
        title: '有人在备注中 @ 你',
        message: m.note || '查看工单协作备注',
        type: 'warning',
        duration: 8000
      });
      if (current.value && m.ticketId === current.value.id) {
        events.value.push(m);
      }
    }
  } catch {
    // 下一轮重试;游标未推进不丢提醒
  }
};

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
  // B4:调查历史与可选数据源随工单一起刷新(失败不影响主面板)
  await loadRcaOptions();
  await loadInvestigations(row.id);
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

// ============ B4 AI 根因调查(RCA) ============
const rcaDatasourceId = ref<number | string | undefined>(undefined);
const datasourceOptions = ref<Array<Record<string, any>>>([]);
const investigations = ref<CsInvestigation[]>([]);
const rcaStarting = ref(false);
const rcaVisible = ref(false);
const rcaDetail = ref<CsInvestigation | null>(null);
let rcaTimer: ReturnType<typeof setInterval> | null = null;

const loadRcaOptions = async () => {
  if (datasourceOptions.value.length) {
    return;
  }
  try {
    const res = await listDatasource({ pageNum: 1, pageSize: 100 });
    datasourceOptions.value = res.data?.rows ?? [];
  } catch {
    datasourceOptions.value = [];
  }
};

const loadInvestigations = async (ticketId: number) => {
  try {
    const res = await listInvestigations(ticketId);
    investigations.value = res.data ?? [];
  } catch {
    investigations.value = [];
  }
};

const stopPolling = () => {
  if (rcaTimer) {
    clearInterval(rcaTimer);
    rcaTimer = null;
  }
};

const refreshDetail = async (id: number) => {
  const res = await getInvestigation(id);
  rcaDetail.value = res.data ?? null;
  const hit = investigations.value.find((r) => r.id === id);
  if (hit && rcaDetail.value) {
    hit.status = rcaDetail.value.status;
  }
  return rcaDetail.value;
};

const startInvestigation = async () => {
  if (!current.value) {
    return;
  }
  if (!rcaDatasourceId.value) {
    ElMessage.warning('请先选择调查用数据源');
    return;
  }
  rcaStarting.value = true;
  try {
    const res = await investigateTicket(current.value.id, rcaDatasourceId.value);
    const invId = res.data as unknown as number;
    ElMessage.success('已发起调查,通常 10~60 秒出结果');
    await loadInvestigations(current.value.id);
    if (invId) {
      rcaVisible.value = true;
      await refreshDetail(invId);
      startPolling(invId);
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '发起调查失败');
  } finally {
    rcaStarting.value = false;
  }
};

const startPolling = (id: number) => {
  stopPolling();
  rcaTimer = setInterval(async () => {
    try {
      const detail = await refreshDetail(id);
      if (detail && detail.status !== 'RUNNING') {
        stopPolling();
      }
    } catch {
      stopPolling();
    }
  }, 2500);
};

const viewInvestigation = async (row: CsInvestigation) => {
  rcaVisible.value = true;
  await refreshDetail(row.id);
  if (rcaDetail.value?.status === 'RUNNING') {
    startPolling(row.id);
  }
};

const decideInvestigation = async (approved: boolean) => {
  if (!rcaDetail.value) {
    return;
  }
  try {
    await confirmInvestigation(rcaDetail.value.id, approved);
    ElMessage.success(approved ? '已采纳并回填工单' : '已驳回');
    await refreshDetail(rcaDetail.value.id);
    if (current.value) {
      await open(current.value);
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败');
  }
};

onUnmounted(() => {
  stopPolling();
  if (mentionTimer) {
    clearInterval(mentionTimer);
    mentionTimer = null;
  }
});

const canReply = () => current.value?.status === 'PROCESSING';

onMounted(() => {
  load();
  pollMentions();
  mentionTimer = setInterval(pollMentions, 20000);
});
</script>

<template>
  <div class="cs-workbench">
    <el-row :gutter="16">
      <el-col :xs="24" :md="9">
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

      <el-col :xs="24" :md="15">
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

            <el-divider content-position="left">AI 根因调查</el-divider>
            <div class="cs-rca">
              <div class="cs-rca-bar">
                <el-select v-model="rcaDatasourceId" size="small" placeholder="调查用数据源" style="width: 220px">
                  <el-option v-for="d in datasourceOptions" :key="d.id" :label="d.name" :value="d.id" />
                </el-select>
                <el-button size="small" type="primary" :loading="rcaStarting" @click="startInvestigation">
                  发起调查
                </el-button>
                <span class="cs-rca-tip">只读取数 + 证据链;报告需坐席确认才回填工单</span>
              </div>
              <el-table v-if="investigations.length" :data="investigations" size="small" border>
                <el-table-column prop="id" label="#" width="60" />
                <el-table-column prop="status" label="状态" width="110" />
                <el-table-column prop="createTime" label="时间" min-width="150" show-overflow-tooltip />
                <el-table-column label="操作" width="80">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="viewInvestigation(row as CsInvestigation)">
                      查看
                    </el-button>
                  </template>
                </el-table-column>
                <template #empty><el-empty description="暂无调查记录" :image-size="50" /></template>
              </el-table>
            </div>

            <el-divider content-position="left">流转轨迹</el-divider>
            <TicketDetail :events="events" />
          </template>
          <el-empty v-else description="从左侧选择一张工单" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>

    <el-drawer v-model="rcaVisible" title="AI 根因调查报告" size="680px" @closed="stopPolling">
      <template v-if="rcaDetail">
        <el-alert :type="rcaDetail.status === 'FAILED' ? 'error' : rcaDetail.status === 'RUNNING' ? 'info' : 'success'"
          :closable="false" show-icon class="cs-rca-status"
          :title="String(rcaDetail.status) + (rcaDetail.errorMessage ? '：' + rcaDetail.errorMessage : '')" />
        <div v-if="rcaDetail.hypotheses?.length" class="cs-rca-block">
          <div class="cs-rca-title">调查假设</div>
          <ul class="cs-rca-list">
            <li v-for="(h, i) in rcaDetail.hypotheses" :key="i">
              <b>{{ h.title }}</b><span v-if="h.rationale"> · {{ h.rationale }}</span>
            </li>
          </ul>
        </div>
        <div v-if="rcaDetail.reportMd" class="cs-rca-block">
          <div class="cs-rca-title">报告</div>
          <pre class="cs-rca-report">{{ rcaDetail.reportMd }}</pre>
        </div>
        <div v-if="rcaDetail.evidences?.length" class="cs-rca-block">
          <div class="cs-rca-title">证据链</div>
          <el-collapse>
            <el-collapse-item v-for="e in rcaDetail.evidences" :key="e.seq"
              :name="e.seq" :title="`#${e.seq} ${e.hypothesisTitle || e.evidenceType}（${e.success ? '成功' : '失败'}）`">
              <div class="cs-rca-code">{{ e.queryText }}</div>
              <div v-if="e.resultDigest" class="cs-rca-code">{{ e.resultDigest }}</div>
              <div v-if="e.errorMessage" class="cs-rca-error">{{ e.errorMessage }}</div>
            </el-collapse-item>
          </el-collapse>
        </div>
        <div v-if="rcaDetail.status === 'SUCCEEDED'" class="cs-rca-actions">
          <el-button size="small" @click="decideInvestigation(false)">驳回</el-button>
          <el-button size="small" type="primary" @click="decideInvestigation(true)">采纳并回填工单</el-button>
        </div>
      </template>
      <el-empty v-else description="加载中或记录不存在" :image-size="70" />
    </el-drawer>
  </div>
</template>

<style scoped>
.cs-rca-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.cs-rca-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-rca-status {
  margin-bottom: 10px;
}
.cs-rca-block {
  margin-bottom: 14px;
}
.cs-rca-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.cs-rca-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
}
.cs-rca-report {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.7;
  background: var(--el-fill-color-lighter);
  padding: 10px;
  border-radius: 4px;
  max-height: 320px;
  overflow: auto;
}
.cs-rca-code {
  font-family: monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}
.cs-rca-error {
  color: var(--el-color-danger);
  font-size: 12px;
}
.cs-rca-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
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
