<script setup lang="ts">
/**
 * 通话记录页(V2 → C8 运营增强):语音域的坐席侧主视图。
 *
 * 数据流:callStats / listCalls / listCallSeats / listCallbackTasks 四个接口拉取
 * 当日指标、通话列表、坐席就绪态与呼损回拨清单;行点击打开详情抽屉
 * (getCallDetail 一次带回主记录 + 事件轨迹 + 转写时间轴,getCallProfile 单独取画像),
 * 录音按需取回(getCallRecording 返回后端短期签名 URL,前端不持有任何存储凭据)。
 *
 * C8 增量:① 指标卡片扩到 11 项(转人工/呼损/等待时长/负面通话/质检均分);
 * ② 抽屉里加「通话画像」面板(意图/情绪/质检/标签/提取结果);
 * ③ 新增呼损回拨清单(状态单向流转,非法流转由后端拒绝并回显原因);
 * ④ 转写时间轴按 segmentKind 区分系统段(转接发起/坐席接通)。
 *
 * 扩展点:坐席侧通话标签、人工补挂工单、月度对账报表都在本页叠加;
 * 列表字段与后端 CsCallController.list 的过滤参数一一对应。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  callStats,
  getCallDetail,
  getCallProfile,
  getCallRecording,
  listCallbackTasks,
  listCallSeats,
  listCalls,
  markCallbackTask
} from '@/api/customerservice';
import type {
  CsCall,
  CsCallbackTask,
  CsCallEvent,
  CsCallMetric,
  CsCallProfile,
  CsCallSeatState,
  CsCallSegment
} from '@/api/customerservice';

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

/* ---------------- C8:画像 / 回拨 ---------------- */
const profile = ref<CsCallProfile | null>(null);
const profileLoading = ref(false);
const callbacks = ref<CsCallbackTask[]>([]);
const callbackFilter = ref('');
const callbackLoading = ref(false);

/** 统计卡片:key 与 CsCallMetric 字段一一对应,新增指标只需扩接口与卡片定义。 */
const cards: Array<{ key: keyof CsCallMetric; label: string; suffix: string }> = [
  { key: 'callCount', label: '今日通话', suffix: '' },
  { key: 'answeredCount', label: '接通数', suffix: '' },
  { key: 'answerRate', label: '接通率', suffix: ' %' },
  { key: 'avgDurationSec', label: '平均时长', suffix: ' 秒' },
  { key: 'botResolveRate', label: '机器人解决率', suffix: ' %' },
  { key: 'recordingCoverage', label: '录音覆盖率', suffix: ' %' },
  /* C8 扩维 */
  { key: 'transferRate', label: '转人工率', suffix: ' %' },
  { key: 'abandonRate', label: '呼损率', suffix: ' %' },
  { key: 'avgWaitSec', label: '平均等待', suffix: ' 秒' },
  { key: 'negativeCalls', label: '负面通话', suffix: ' 通' },
  { key: 'qualityAvgScore', label: '质检均分', suffix: ' 分' }
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

/** 画像标签集合(后端以逗号分隔存的一行文本)。 */
const profileTags = computed<string[]>(() => {
  const raw = profile.value?.tags;
  return raw ? raw.split(',').filter((item) => item.trim().length > 0) : [];
});

/** 提取结果:payload 是 JSON 字符串,坏数据只影响这一块,不影响整张画像。 */
const profileExtract = computed<Array<{ key: string; value: string }>>(() => {
  const raw = profile.value?.payload;
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw) as { extract?: Record<string, string> };
    return Object.entries(parsed.extract ?? {}).map(([key, value]) => ({ key, value }));
  } catch {
    return [];
  }
});

const sentimentTag = (label: string | null): 'success' | 'info' | 'danger' => {
  if (label === 'NEGATIVE') {
    return 'danger';
  }
  return label === 'POSITIVE' ? 'success' : 'info';
};

const callbackStatusTag = (status: string): 'warning' | 'primary' | 'success' => {
  if (status === 'PENDING') {
    return 'warning';
  }
  return status === 'CALLED' ? 'primary' : 'success';
};

const callbackStatusLabel = (status: string): string => {
  const map: Record<string, string> = { PENDING: '待回拨', CALLED: '已回拨', CLOSED: '已关闭' };
  return map[status] ?? status;
};

const reasonLabel = (reason: string): string =>
  reason === 'ABANDONED' ? '排队放弃' : '振铃未接';

/** 系统段时间轴的展示名(C8):与后端 segment_kind 一一对应。 */
const segmentKindLabel = (kind: string | null): string => {
  if (kind === 'TRANSFER_RING') {
    return '转接发起';
  }
  return kind === 'TRANSFER_CONNECT' ? '坐席接通' : '';
};

const metricValue = (key: keyof CsCallMetric): number | string => {
  const value = metric.value?.[key];
  return value === null || value === undefined ? 0 : value;
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

const loadCallbacks = async () => {
  callbackLoading.value = true;
  try {
    const res = await listCallbackTasks(callbackFilter.value || undefined);
    callbacks.value = res.data ?? [];
  } catch {
    ElMessage.error('呼损回拨清单加载失败');
  } finally {
    callbackLoading.value = false;
  }
};

const openDetail = async (row: CsCall) => {
  drawerVisible.value = true;
  recordingUrl.value = '';
  profile.value = null;
  detailCall.value = row;
  detailEvents.value = [];
  detailSegments.value = [];
  try {
    const res = await getCallDetail(row.id);
    detailCall.value = res.data?.call ?? row;
    detailEvents.value = res.data?.events ?? [];
    detailSegments.value = res.data?.segments ?? [];
  } catch {
    ElMessage.error('通话详情加载失败');
  }
  profileLoading.value = true;
  try {
    // 历史通话没有画像时后端会按洞察现算一份,这里不需要区分「有/无」
    const res = await getCallProfile(row.id);
    profile.value = res.data ?? null;
  } catch {
    ElMessage.warning('通话画像暂不可用');
  } finally {
    profileLoading.value = false;
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

/**
 * 回拨状态流转:备注用 prompt 收集(可留空)。
 * 取消弹窗不算错误;接口失败由请求拦截器统一提示。
 */
const markCallback = async (row: CsCallbackTask, status: 'CALLED' | 'CLOSED') => {
  const editing = status === 'CALLED';
  let remark = '';
  try {
    const result = await ElMessageBox.prompt(
      editing ? '记录本次回拨情况(可留空)' : '填写关闭原因(可留空)',
      editing ? '标记已回拨' : '关闭回拨单',
      {
        inputPlaceholder: '可选,最多 200 字',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }
    );
    remark = result.value ?? '';
  } catch {
    return;
  }
  try {
    await markCallbackTask(row.id, status, remark || undefined);
    ElMessage.success(editing ? '已标记回拨' : '回拨单已关闭');
    await loadCallbacks();
  } catch {
    // 非法流转等原因由拦截器提示,这里不再重复弹窗
  }
};

onMounted(async () => {
  await load();
  await loadCallbacks();
});
</script>

<template>
  <div class="cs-page">
    <div class="cs-stats">
      <el-card v-for="item in cards" :key="item.key" shadow="never" class="cs-stat">
        <div class="cs-label">{{ item.label }}</div>
        <div class="cs-value">{{ metricValue(item.key) }}{{ item.suffix }}</div>
      </el-card>
    </div>

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
      <template #header>
        <div class="cs-header">
          <span>呼损回拨清单</span>
          <div class="cs-actions">
            <el-select v-model="callbackFilter" placeholder="全部状态" clearable size="small" style="width: 130px" @change="loadCallbacks">
              <el-option label="待回拨" value="PENDING" />
              <el-option label="已回拨" value="CALLED" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
            <el-button size="small" @click="loadCallbacks">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="callbackLoading" :data="callbacks" size="small">
        <el-table-column prop="callerNumber" label="主叫" width="140" />
        <el-table-column label="原因" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ reasonLabel(row.reason) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="callbackStatusTag(row.status)">{{ callbackStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="attemptCount" label="已拨次" width="90" />
        <el-table-column prop="lastAttemptAt" label="最近回拨" width="170" />
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column prop="createTime" label="建单时间" width="170" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'CLOSED'" link type="primary" size="small" @click="markCallback(row as CsCallbackTask, 'CALLED')">标记已回拨</el-button>
            <el-button v-if="row.status !== 'CLOSED'" link type="info" size="small" @click="markCallback(row as CsCallbackTask, 'CLOSED')">关闭</el-button>
            <span v-if="row.status === 'CLOSED'" class="cs-muted">已归档</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!callbackLoading && !callbacks.length" description="暂无呼损通话" :image-size="60" />
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

    <el-drawer v-model="drawerVisible" title="通话详情" size="560px">
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
          <div class="cs-header"><span>通话画像</span></div>
          <div v-loading="profileLoading">
            <template v-if="profile">
              <div class="cs-summary">{{ profile.summary ?? '暂无摘要' }}</div>
              <div v-if="profileTags.length" class="cs-tags">
                <el-tag v-for="tag in profileTags" :key="tag" size="small" type="warning" effect="plain">{{ tag }}</el-tag>
              </div>
              <el-descriptions :column="2" border size="small" class="cs-profile-grid">
                <el-descriptions-item label="意图">{{ profile.intentCode ?? '未识别' }}</el-descriptions-item>
                <el-descriptions-item label="情绪">
                  <el-tag size="small" :type="sentimentTag(profile.sentimentLabel)">{{ profile.sentimentLabel ?? '未知' }}</el-tag>
                  <span class="cs-muted"> {{ profile.sentimentScore ?? '-' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="质检分">{{ profile.qualityScore ?? '未质检' }}</el-descriptions-item>
                <el-descriptions-item label="主叫轮次">{{ profile.turnCount ?? 0 }}</el-descriptions-item>
                <el-descriptions-item label="通话时长">{{ profile.durationSec ?? '-' }} 秒</el-descriptions-item>
                <el-descriptions-item label="处理结果">{{ profile.resolvedFlag === '1' ? '机器人自助' : (profile.transferFlag === '1' ? '转人工' : '未解决') }}</el-descriptions-item>
              </el-descriptions>
              <div v-if="profileExtract.length" class="cs-tags">
                <span class="cs-label">提取信息</span>
                <el-tag v-for="item in profileExtract" :key="item.key" size="small" effect="plain">{{ item.key }}: {{ item.value }}</el-tag>
              </div>
            </template>
            <div v-else-if="!profileLoading" class="cs-muted">该通话暂无画像（挂机后由洞察管道生成，无转写时不产出）</div>
          </div>
        </div>

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
            <el-tag v-if="segmentKindLabel(seg.segmentKind)" size="small" type="warning">{{ segmentKindLabel(seg.segmentKind) }}</el-tag>
            <el-tag v-else size="small" :type="seg.speaker === 'bot' ? 'warning' : seg.speaker === 'seat' ? 'success' : 'info'">{{ seg.speaker }}</el-tag>
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
.cs-stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
.cs-stat :deep(.el-card__body) { padding: 12px 14px; }
.cs-label { font-size: 12px; color: var(--el-text-color-secondary); }
.cs-value { font-size: 22px; font-weight: 500; margin-top: 6px; }
.cs-section { margin-top: 16px; }
.cs-muted { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 8px; }
.cs-seg { margin-top: 8px; display: flex; gap: 8px; align-items: flex-start; }
.cs-seg-text { font-size: 13px; line-height: 1.5; }
.cs-summary { font-size: 13px; line-height: 1.6; margin-top: 8px; }
.cs-tags { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
.cs-profile-grid { margin-top: 8px; }
</style>
