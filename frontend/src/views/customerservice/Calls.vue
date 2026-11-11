<script setup lang="ts">
/**
 * 通话记录页(V2):语音域的坐席侧主视图。
 *
 * 数据流:callStats / listCalls / listCallSeats 三个接口拉取当日指标、
 * 通话列表与坐席就绪态;行点击打开详情抽屉(getCallDetail 一次带回
 * 主记录 + 事件轨迹 + 转写时间轴),录音按需取回(getCallRecording 返回
 * 后端短期签名 URL,前端不持有任何存储凭据)。
 *
 * 扩展点:坐席侧通话标签、人工补挂工单、月度对账报表都在本页叠加;
 * 列表字段与后端 CsCallController.list 的过滤参数一一对应。
 */
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  callStats,
  getCallDetail,
  getCallRecording,
  listCallSeats,
  listCalls
} from '@/api/customerservice';
import type { CsCall, CsCallEvent, CsCallMetric, CsCallSeatState, CsCallSegment } from '@/api/customerservice';

const rows = ref<CsCall[]>([]);
const metric = ref<CsCallMetric | null>(null);
const seats = ref<CsCallSeatState[]>([]);
const loading = ref(false);
const statusFilter = ref('');
const drawerVisible = ref(false);
const detailCall = ref<CsCall | null>(null);
const detailEvents = ref<CsCallEvent[]>([]);
const detailSegments = ref<CsCallSegment[]>([]);
const recordingUrl = ref('');
const recordingLoading = ref(false);

/** 统计卡片:key 与 CsCallMetric 字段一一对应,新增指标只需扩接口与卡片定义。 */
const cards: Array<{ key: keyof CsCallMetric; label: string; suffix: string }> = [
  { key: 'callCount', label: '今日通话', suffix: '' },
  { key: 'answeredCount', label: '接通数', suffix: '' },
  { key: 'answerRate', label: '接通率', suffix: ' %' },
  { key: 'avgDurationSec', label: '平均时长', suffix: ' 秒' },
  { key: 'botResolveRate', label: '机器人解决率', suffix: ' %' },
  { key: 'recordingCoverage', label: '录音覆盖率', suffix: ' %' }
];

const statusTag = (status: string): 'success' | 'info' | 'warning' | 'danger' | 'primary' => {
  /** 8 态呼叫状态 → Element 标签色;新状态加一行即可。 */
  const map: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'primary'> = {
    RINGING: 'info',
    ANSWERED: 'primary',
    BOT_SERVING: 'warning',
    WAITING_AGENT: 'warning',
    AGENT_SERVING: 'success',
    HOLD: 'info',
    WRAP_UP: 'info',
    ENDED: 'info'
  };
  return map[status] ?? 'info';
};

const load = async () => {
  loading.value = true;
  try {
    const [listRes, statsRes, seatRes] = await Promise.all([
      listCalls(statusFilter.value || undefined),
      callStats(),
      listCallSeats()
    ]);
    rows.value = listRes.data ?? [];
    metric.value = statsRes.data ?? null;
    seats.value = seatRes.data ?? [];
  } catch {
    ElMessage.error('通话数据加载失败');
  } finally {
    loading.value = false;
  }
};

const openDetail = async (row: CsCall) => {
  drawerVisible.value = true;
  recordingUrl.value = '';
  try {
    const res = await getCallDetail(row.id);
    detailCall.value = res.data?.call ?? null;
    detailEvents.value = res.data?.events ?? [];
    detailSegments.value = res.data?.segments ?? [];
  } catch {
    ElMessage.error('通话详情加载失败');
  }
};

const playRecording = async () => {
  if (!detailCall.value) {
    return;
  }
  recordingLoading.value = true;
  try {
    const res = await getCallRecording(detailCall.value.id);
    recordingUrl.value = res.data?.url ?? '';
    if (!recordingUrl.value) {
      ElMessage.warning('录音尚未就绪或已被留存清理');
    }
  } catch {
    ElMessage.error('录音取回失败');
  } finally {
    recordingLoading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-row :gutter="12">
      <el-col v-for="item in cards" :key="item.key" :span="4">
        <el-card shadow="never">
          <div class="cs-label">{{ item.label }}</div>
          <div class="cs-value">{{ metric?.[item.key] ?? 0 }}{{ item.suffix }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>通话记录</span>
          <div class="cs-actions">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable size="small" style="width: 150px" @change="load">
              <el-option v-for="s in ['RINGING', 'ANSWERED', 'BOT_SERVING', 'WAITING_AGENT', 'AGENT_SERVING', 'HOLD', 'WRAP_UP', 'ENDED']" :key="s" :label="s" :value="s" />
            </el-select>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="loading" :data="rows" size="small" @row-click="openDetail">
        <el-table-column prop="callId" label="呼叫标识" min-width="180" show-overflow-tooltip />
        <el-table-column prop="callerNumber" label="主叫" width="130" />
        <el-table-column prop="calleeNumber" label="被叫" width="130" />
        <el-table-column prop="direction" label="方向" width="90" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="workNo" label="坐席工号" width="100" />
        <el-table-column prop="durationSec" label="时长(秒)" width="90" />
        <el-table-column prop="ringAt" label="呼入时间" width="170" />
        <el-table-column label="录音" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.recordingOssKey" size="small" type="success">已存</el-tag>
            <el-tag v-else size="small" type="info">无</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无通话记录" :image-size="70" />
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header><span>电话坐席就绪态</span></template>
      <el-table :data="seats" size="small">
        <el-table-column prop="workNo" label="工号" width="140" />
        <el-table-column prop="readyState" label="就绪态" width="120" />
        <el-table-column prop="pauseReason" label="小休原因" min-width="140" />
        <el-table-column prop="loginAt" label="签入时间" width="170" />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
      </el-table>
      <el-empty v-if="!seats.length" description="暂无坐席签入" :image-size="60" />
    </el-card>

    <el-drawer v-model="drawerVisible" title="通话详情" size="520px">
      <template v-if="detailCall">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="呼叫标识">{{ detailCall.callId }}</el-descriptions-item>
          <el-descriptions-item label="主叫 / 被叫">{{ detailCall.callerNumber }} → {{ detailCall.calleeNumber }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detailCall.status }}</el-descriptions-item>
          <el-descriptions-item label="坐席工号">{{ detailCall.workNo ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="挂机原因">{{ detailCall.hangupCause ?? '-' }}（{{ detailCall.hangupBy ?? '-' }}）</el-descriptions-item>
          <el-descriptions-item label="关联工单">{{ detailCall.ticketId ?? '无' }}</el-descriptions-item>
          <el-descriptions-item label="关联会话">{{ detailCall.conversationId ?? '无' }}</el-descriptions-item>
        </el-descriptions>
        <div class="cs-section">
          <div class="cs-header">
            <span>录音回听</span>
            <el-button size="small" :loading="recordingLoading" :disabled="!detailCall.recordingOssKey" @click="playRecording">取回并播放</el-button>
          </div>
          <audio v-if="recordingUrl" :src="recordingUrl" controls style="width: 100%; margin-top: 8px" />
          <div v-else class="cs-muted">{{ detailCall.recordingOssKey ? '点击按钮取回短期签名地址' : '该通话无录音' }}</div>
        </div>
        <div class="cs-section">
          <div class="cs-header"><span>转写时间轴</span></div>
          <div v-for="seg in detailSegments" :key="seg.id" class="cs-seg">
            <el-tag size="small" :type="seg.speaker === 'bot' ? 'warning' : seg.speaker === 'seat' ? 'success' : 'info'">{{ seg.speaker }}</el-tag>
            <span class="cs-seg-text">{{ seg.text }}</span>
          </div>
          <el-empty v-if="!detailSegments.length" description="无转写" :image-size="50" />
        </div>
        <div class="cs-section">
          <div class="cs-header"><span>事件轨迹</span></div>
          <el-timeline>
            <el-timeline-item v-for="ev in detailEvents" :key="ev.id" :timestamp="ev.createTime ?? ''">
              {{ ev.eventType }}
              <template v-if="ev.toStatus">（{{ ev.fromStatus ?? '-' }} → {{ ev.toStatus }}）</template>
            </el-timeline-item>
          </el-timeline>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-card { margin-top: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-actions { display: flex; gap: 8px; align-items: center; }
.cs-label { font-size: 12px; color: var(--el-text-color-secondary); }
.cs-value { font-size: 22px; font-weight: 500; margin-top: 6px; }
.cs-section { margin-top: 16px; }
.cs-muted { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 8px; }
.cs-seg { margin-top: 8px; display: flex; gap: 8px; align-items: flex-start; }
.cs-seg-text { font-size: 13px; line-height: 1.5; }
</style>
