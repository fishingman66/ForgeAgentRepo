<script setup lang="ts">
/**
 * 会话消息面板(C1/C4/A1/A2):坐席回复输入 + 推荐话术卡片 + 通话实时转写。
 *
 * 数据流:打开面板时 listSuggestions 拉历史推荐填充卡片;此后以
 * listConversationEvents(after 游标)2s 轮询增量消费会话事件流——
 * 与访客侧同一 RunEventBuffer 通道,断线续传天然支持。
 * 事件分工:cs_seat_suggest 驱动推荐卡片(按 suggestionId 去重);
 * call.transcript 驱动转写上屏(A2,中间结果覆盖同人未定稿行);
 * call.ring / call.hangup 维护「通话中」标识。
 * 采纳 = 话术填入输入框、保留修改权,由坐席手动发送(方案 v1.1 定稿)。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  addTicketNote,
  executeSuggestionAction,
  feedbackSuggestion,
  getConversationAttachment,
  listConversationAttachments,
  listConversationEvents,
  listAnswers,
  listSeats,
  listSuggestions,
  replyTicket,
  rewriteText,
  streamConversationEvents
} from '@/api/customerservice';
import type { CsAttachmentBrief, CsKnowledgeAnswer, CsSeatSuggestion } from '@/api/customerservice';

const props = defineProps<{ ticketId: number; conversationId: number | null; disabled: boolean }>();
const emit = defineEmits<{ (e: 'replied'): void }>();

/** 转写面板保留的行数:一轮通话的上下文够看即可,避免长通话撑爆面板。 */
const MAX_TRANSCRIPTS = 12;

const content = ref('');
/** A3:对客回复 / 内部备注分栏。备注走 NOTE 事件,访客不可见。 */
const messageMode = ref<'reply' | 'note'>('reply');
const rewriting = ref(false);
const noteSaving = ref(false);
/** A3:快捷话术(复用已生效 FAQ)与 @ 提及候选坐席。 */
const quickPhrases = ref<CsKnowledgeAnswer[]>([]);
const quickOpen = ref(false);
const mentionOpen = ref(false);
const mentionQuery = ref('');
const mentionSeats = ref<Array<{ userId: number; seatGroup?: string; online?: string }>>([]);
const mentionIndex = ref(0);
/** @ 查询起始位置(-1 表示未激活);watch content 时定位未闭合的 @。 */
let mentionStart = -1;
const sending = ref(false);
const suggestions = ref<CsSeatSuggestion[]>([]);
const attachments = ref<CsAttachmentBrief[]>([]);
const preview = ref<{ visible: boolean; src: string; name: string }>({
  visible: false,
  src: '',
  name: ''
});
const transcripts = ref<TranscriptLine[]>([]);
const callActive = ref(false);
const acting = ref<number | null>(null);
const cursor = ref(0);
const seenSuggestionIds = new Set<number>();
let transcriptSeq = 0;
let pollTimer: number | undefined;

interface SuggestItem {
  type: string;
  title: string;
  content: string;
  score: number;
  sourceId: string | null;
}

interface TranscriptLine {
  key: number;
  speaker: string;
  text: string;
  final: boolean;
}

/** 建议动作(A4):REPLY/LINK 发消息,RESOLVE 标记解决;code 是服务端白名单键。 */
interface SuggestAction {
  code: string;
  label: string;
  payload?: Record<string, unknown>;
}

/** A3:@ 后按输入过滤坐席候选(按 userId / 分组名匹配)。 */
const mentionCandidates = computed(() => {
  const q = mentionQuery.value.trim().toLowerCase();
  const all = mentionSeats.value.filter((s) => s.userId != null);
  if (!q) {
    return all.slice(0, 8);
  }
  return all.filter((s) => String(s.userId).includes(q)
    || (s.seatGroup || '').toLowerCase().includes(q)).slice(0, 8);
});

const parseActions = (raw: string): SuggestAction[] => {
  try {
    const parsed = JSON.parse(raw) as SuggestAction[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
};

const parseItems = (raw: string): SuggestItem[] => {
  try {
    return JSON.parse(raw) as SuggestItem[];
  } catch {
    return [];
  }
};

/**
 * 事件端点的 data 是信封 JSON 字符串(含 type/sequence/payload),
 * 业务字段在 payload 里;兼容后端未来改为直接下发 payload 的情形。
 */
const payloadOf = (raw: unknown): Record<string, unknown> => {
  let data: unknown = raw;
  if (typeof data === 'string') {
    try {
      data = JSON.parse(data);
    } catch {
      return {};
    }
  }
  if (!data || typeof data !== 'object') {
    return {};
  }
  const envelope = data as Record<string, unknown>;
  const payload = envelope.payload;
  return payload && typeof payload === 'object'
    ? (payload as Record<string, unknown>)
    : envelope;
};

/** A3:加载快捷话术(已生效 FAQ)与可提及坐席,失败静默(辅助能力不阻断回复)。 */
const loadCopilotData = async () => {
  try {
    const [answers, seats] = await Promise.all([listAnswers('ACTIVE'), listSeats()]);
    quickPhrases.value = answers?.data ?? [];
    mentionSeats.value = (seats as unknown as { data?: typeof mentionSeats.value })?.data ?? [];
  } catch {
    quickPhrases.value = [];
    mentionSeats.value = [];
  }
};

const loadHistory = async () => {
  if (!props.conversationId) {
    return;
  }
  try {
    const res = await listSuggestions(props.conversationId);
    const rows = res.data ?? [];
    for (const row of rows) {
      seenSuggestionIds.add(row.id);
    }
    suggestions.value = rows.filter((s) => s.adopted === '0');
  } catch {
    // 历史拉取失败不阻塞实时流,后续事件会补
  }
  // 客户附件:附件不进模型上下文,坐席在这里看图/看单据
  try {
    const res = await listConversationAttachments(props.conversationId);
    attachments.value = res.data ?? [];
  } catch {
    attachments.value = [];
  }
};

/** 图片取 thumb 预览，文档只提示已接收。 */
const openAttachment = async (row: CsAttachmentBrief) => {
  if (!props.conversationId) {
    return;
  }
  try {
    const res = await getConversationAttachment(props.conversationId, row.id);
    const vo = res.data as { thumb?: string; summary?: string } | null;
    if (vo?.thumb) {
      preview.value = { visible: true, src: vo.thumb, name: row.name };
      return;
    }
    ElMessage.info(vo?.summary ? `文件摘要：${vo.summary}` : '该文件已接收，坐席可直接查看');
  } catch {
    ElMessage.error('附件加载失败');
  }
};

const attachmentSize = (bytes: number) => {
  if (!bytes) {
    return '-';
  }
  return bytes > 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`;
};

const applySuggestion = (data: Record<string, unknown>) => {
  const sid = Number(data?.suggestionId);
  if (!sid || seenSuggestionIds.has(sid)) {
    return;
  }
  seenSuggestionIds.add(sid);
  suggestions.value = [
    ...suggestions.value,
    {
      id: sid,
      conversationId: props.conversationId ?? 0,
      ticketId: null,
      intent: (data?.intent as string | null) ?? null,
      intentName: (data?.intentName as string | null) ?? null,
      items: JSON.stringify(data?.items ?? []),
      actions: JSON.stringify(data?.actions ?? []),
      source: (data?.source as string | null) ?? null,
      callId: null,
      topScore: null,
      adopted: '0',
      adoptAction: null,
      createTime: null
    }
  ];
};

/**
 * 转写上屏:中间结果(isFinal=false)覆盖同一说话人的最后一行,定稿则落定。
 * 后端已按渠道开关推送脱敏文本,前端只负责呈现,不再做二次处理。
 */
const appendTranscript = (data: Record<string, unknown>) => {
  const text = String(data?.text ?? '').trim();
  if (!text) {
    return;
  }
  const speaker = String(data?.speaker || 'caller');
  const final = Boolean(data?.isFinal);
  const last = transcripts.value[transcripts.value.length - 1];
  if (last && last.speaker === speaker && !last.final) {
    transcripts.value = transcripts.value.map((line) =>
      line.key === last.key ? { ...line, text, final } : line
    );
    return;
  }
  transcripts.value = [
    ...transcripts.value,
    { key: ++transcriptSeq, speaker, text, final }
  ].slice(-MAX_TRANSCRIPTS);
};

/**
 * 事件分发(轮询与 SSE 共用):后端推的是完整信封 JSON,业务字段在 payload 里。
 * 新增事件类型只改这一处。
 */
// SSE 下发 JSON 字符串,轮询接口下发对象;payloadOf 已兼容两者,这里不收窄
const dispatchEvent = (eventName: string, raw: unknown) => {
  if (eventName === 'cs_seat_suggest') {
    applySuggestion(payloadOf(raw));
  } else if (eventName === 'call.transcript') {
    appendTranscript(payloadOf(raw));
  } else if (eventName === 'call.ring') {
    callActive.value = true;
  } else if (eventName === 'call.hangup') {
    callActive.value = false;
  }
};

const pollEvents = async () => {
  if (!props.conversationId || props.disabled) {
    return;
  }
  try {
    const res = await listConversationEvents(props.conversationId, cursor.value);
    const events = res.data ?? [];
    for (const ev of events) {
      cursor.value = Math.max(cursor.value, Number(ev.id) || 0);
      dispatchEvent(ev.event, ev.data);
    }
  } catch {
    // 拉取失败下个周期重试,游标未推进不丢事件
  }
};

const resetStream = () => {
  cursor.value = 0;
  seenSuggestionIds.clear();
  suggestions.value = [];
  transcripts.value = [];
  callActive.value = false;
  loadCopilotData();
  loadHistory();
  pollEvents();
};

const adopt = async (item: SuggestItem, suggestion: CsSeatSuggestion) => {
  content.value = item.content;
  suggestions.value = suggestions.value.filter((s) => s.id !== suggestion.id);
  try {
    await feedbackSuggestion(suggestion.id, 'send');
  } catch {
    // 埋点失败不影响采纳体验
  }
};

const copy = async (item: SuggestItem, suggestion: CsSeatSuggestion) => {
  try {
    await navigator.clipboard.writeText(item.content);
    ElMessage.success('已复制到剪贴板');
    suggestions.value = suggestions.value.filter((s) => s.id !== suggestion.id);
    await feedbackSuggestion(suggestion.id, 'copy');
  } catch {
    ElMessage.error('复制失败');
  }
};

const ignore = async (suggestion: CsSeatSuggestion) => {
  suggestions.value = suggestions.value.filter((s) => s.id !== suggestion.id);
  try {
    await feedbackSuggestion(suggestion.id, 'ignore');
  } catch {
    // 忽略埋点失败不影响交互
  }
};

/**
 * 动作执行(A4):必须先经坐席二次确认,服务端只接受推荐快照里的动作码。
 * 执行成功后移除该推荐——动作已作用到工单/会话上,卡片留着只会诱导重复操作。
 */
const executeAction = async (suggestion: CsSeatSuggestion, action: SuggestAction) => {
  try {
    await ElMessageBox.confirm(
      `确认执行「${action.label}」？该操作会直接作用于当前工单。`,
      '执行确认',
      { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  acting.value = suggestion.id;
  try {
    await executeSuggestionAction(suggestion.id, action.code);
    ElMessage.success('已执行');
    suggestions.value = suggestions.value.filter((s) => s.id !== suggestion.id);
    emit('replied');
  } catch {
    ElMessage.error('执行失败');
  } finally {
    acting.value = null;
  }
};

const submit = async () => {
  if (!content.value.trim() || props.disabled) {
    return;
  }
  sending.value = true;
  try {
    await replyTicket(props.ticketId, content.value.trim());
    content.value = '';
    emit('replied');
  } catch {
    ElMessage.error('回复失败');
  } finally {
    sending.value = false;
  }
};

let streamAbort: AbortController | undefined;
let streamFailures = 0;

/**
 * 实时通道(P3):优先 SSE 长连接,带游标续传。
 *
 * 服务端到期(10 分钟)会自动收口,这里带新游标重连;
 * 连续三次连不上(代理不支持流式/后端异常)退回 2 秒轮询,保证坐席仍能看到事件。
 */
const startStream = async () => {
  if (!props.conversationId || props.disabled) {
    return;
  }
  streamAbort?.abort();
  const controller = new AbortController();
  streamAbort = controller;
  try {
    await streamConversationEvents(props.conversationId, cursor.value, (name, data, seq) => {
      if (seq > 0) {
        cursor.value = Math.max(cursor.value, seq);
      }
      dispatchEvent(name, data);
    }, controller.signal);
    streamFailures = 0;
    if (!controller.signal.aborted) {
      window.setTimeout(startStream, 300);
    }
  } catch {
    if (controller.signal.aborted) {
      return;
    }
    streamFailures += 1;
    if (streamFailures >= 3) {
      pollTimer = window.setInterval(pollEvents, 2000);
      return;
    }
    window.setTimeout(startStream, 1000);
  }
};

// 切会话要断开旧连接并带新会话游标重连
watch(
  () => props.conversationId,
  () => {
    resetStream();
    startStream();
  }
);

/** A3:监听 @ 输入,维护提及候选弹层;仅在未闭合 @ 时激活。 */
watch(content, (val) => {
  if (messageMode.value !== 'reply') {
    mentionOpen.value = false;
    return;
  }
  const at = val.lastIndexOf('@');
  if (at < 0) {
    mentionStart = -1;
    mentionOpen.value = false;
    return;
  }
  const tail = val.slice(at + 1);
  if (/\s/.test(tail) || tail.length > 20) {
    mentionStart = -1;
    mentionOpen.value = false;
    return;
  }
  mentionStart = at;
  mentionQuery.value = tail;
  mentionIndex.value = 0;
  mentionOpen.value = mentionCandidates.value.length > 0;
});

/** A3:插入 @提及,替换 content 里未闭合的 @片段。 */
const insertMention = (seat: { userId: number }) => {
  if (mentionStart < 0) {
    return;
  }
  const head = content.value.slice(0, mentionStart);
  const tail = content.value.slice(mentionStart + 1 + mentionQuery.value.length);
  content.value = `${head}@${seat.userId} ${tail}`.slice(0, 2000);
  mentionOpen.value = false;
  mentionStart = -1;
};

/** A3:插入快捷话术到输入框末尾(可继续编辑),与推荐卡片共存。 */
const insertPhrase = (answer: CsKnowledgeAnswer) => {
  const addition = (answer.answer || '').trim();
  if (!addition) {
    return;
  }
  content.value = (content.value.trimEnd() + (content.value.trim() ? '\n' : '') + addition).slice(0, 2000);
  quickOpen.value = false;
};

/** A3:AI 润色 / 中英互译;模型不可用(返回空)时保留原文并提示。 */
const rewrite = async (mode: string) => {
  const raw = content.value.trim();
  if (!raw || !props.conversationId || props.disabled) {
    return;
  }
  rewriting.value = true;
  try {
    const res = await rewriteText(props.conversationId, raw, mode);
    const text = (res as unknown as string) || (res as { data?: string })?.data;
    if (typeof text === 'string' && text.trim()) {
      content.value = text.trim().slice(0, 2000);
      ElMessage.success(mode === 'polish' ? '已润色' : '已翻译');
    } else {
      ElMessage.warning('改写暂不可用,已保留原文');
    }
  } catch {
    ElMessage.warning('改写暂不可用,已保留原文');
  } finally {
    rewriting.value = false;
  }
};

/** A3:提交内部备注(仅工单事件轨迹,不进访客通道)。 */
const submitNote = async () => {
  const text = content.value.trim();
  if (!text || props.disabled) {
    return;
  }
  noteSaving.value = true;
  try {
    await addTicketNote(props.ticketId, text);
    content.value = '';
    ElMessage.success('内部备注已保存');
  } catch {
    ElMessage.error('备注保存失败');
  } finally {
    noteSaving.value = false;
  }
};

onMounted(() => {
  resetStream();
  startStream();
});

onUnmounted(() => {
  streamAbort?.abort();
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer);
  }
});
</script>

<template>
  <div class="cs-chat">
    <div class="cs-chat-tip">坐席回复会写入会话消息，并记录首响耗时</div>
    <div v-if="attachments.length" class="cs-attachments">
      <div class="cs-attachments-title">
        客户附件 · {{ attachments.length }} 个（附件不进模型上下文，需坐席确认）
      </div>
      <div class="cs-attachment-list">
        <div v-for="a in attachments" :key="a.id" class="cs-attachment" @click="openAttachment(a)">
          <el-tag size="small" :type="a.kind === 'image' ? 'success' : 'info'">{{ a.fileExt || a.kind }}</el-tag>
          <span class="cs-attachment-name">{{ a.name }}</span>
          <span class="cs-attachment-size">{{ attachmentSize(a.fileSize) }}</span>
          <el-tag v-if="a.parseStatus === '2'" size="small" type="danger">解析失败</el-tag>
        </div>
      </div>
    </div>
    <div v-if="callActive || transcripts.length" class="cs-transcript">
      <div class="cs-transcript-title">
        <el-tag v-if="callActive" type="danger" size="small" effect="dark">通话中</el-tag>
        <span>实时转写</span>
      </div>
      <div v-for="line in transcripts" :key="line.key" class="cs-transcript-line">
        <span class="cs-transcript-speaker" :class="line.speaker === 'agent' ? 'is-agent' : 'is-caller'">
          {{ line.speaker === 'agent' ? '坐席' : '客户' }}
        </span>
        <span class="cs-transcript-text" :class="{ 'is-partial': !line.final }">{{ line.text }}</span>
      </div>
    </div>
    <div v-if="suggestions.length" class="cs-suggest">
      <div class="cs-suggest-title">推荐话术 · 点击采纳后填入输入框，可修改后发送</div>
      <div v-for="s in suggestions" :key="s.id" class="cs-suggest-group">
        <div class="cs-suggest-meta">
          <el-tag v-if="s.intentName || s.intent" size="small" type="warning" effect="plain">
            {{ s.intentName || s.intent }}
          </el-tag>
          <span v-if="s.source === 'voice'" class="cs-suggest-origin">来自通话转写</span>
        </div>
        <div
          v-for="(item, index) in parseItems(s.items)"
          :key="index"
          class="cs-suggest-card"
          @click="adopt(item, s)"
        >
          <el-tag size="small" :type="item.type === 'faq' ? 'success' : 'info'" class="cs-suggest-tag">
            {{ item.type === 'faq' ? 'FAQ' : '知识库' }}
          </el-tag>
          <span class="cs-suggest-content">{{ item.content }}</span>
          <span class="cs-suggest-score">{{ item.score }}</span>
        </div>
        <div class="cs-suggest-more">
          <el-button link size="small" @click.stop="copy(parseItems(s.items)[0], s)">复制第一条</el-button>
          <el-button link size="small" type="info" @click.stop="ignore(s)">不再推荐</el-button>
        </div>
        <div v-if="parseActions(s.actions).length" class="cs-suggest-actions">
          <el-button
            v-for="a in parseActions(s.actions)"
            :key="a.code"
            size="small"
            type="primary"
            plain
            :loading="acting === s.id"
            @click.stop="executeAction(s, a)"
          >{{ a.label }}</el-button>
        </div>
      </div>
    </div>
    <div class="cs-compose">
      <div class="cs-compose-tabs">
        <el-radio-group v-model="messageMode" size="small" :disabled="disabled">
          <el-radio-button value="reply">对客回复</el-radio-button>
          <el-radio-button value="note">内部备注</el-radio-button>
        </el-radio-group>
        <span v-if="messageMode === 'note'" class="cs-compose-note-tip">仅坐席可见,客户不会收到</span>
      </div>

      <div v-if="messageMode === 'reply'" class="cs-compose-tools">
        <el-button link size="small" :disabled="disabled" @click="quickOpen = !quickOpen">/ 快捷话术</el-button>
        <el-button link size="small" :loading="rewriting" :disabled="disabled || !content.trim()" @click="rewrite('polish')">AI 润色</el-button>
        <el-button link size="small" :loading="rewriting" :disabled="disabled || !content.trim()" @click="rewrite('translate_en')">译英</el-button>
        <el-button link size="small" :loading="rewriting" :disabled="disabled || !content.trim()" @click="rewrite('translate_zh')">译中</el-button>
        <span class="cs-compose-hint">输入 @ 提及坐席</span>
      </div>

      <div v-if="quickOpen && messageMode === 'reply'" class="cs-quick-panel">
        <div v-if="!quickPhrases.length" class="cs-quick-empty">暂无已生效的快捷话术</div>
        <div v-for="a in quickPhrases" :key="a.id" class="cs-quick-item" @click="insertPhrase(a)">
          <span class="cs-quick-q">{{ a.question }}</span>
          <span class="cs-quick-a">{{ a.answer }}</span>
        </div>
      </div>

      <div v-if="mentionOpen && mentionCandidates.length" class="cs-mention-panel">
        <div v-for="(s, i) in mentionCandidates" :key="s.userId"
             class="cs-mention-item" :class="{ 'is-active': i === mentionIndex }"
             @click="insertMention(s)">
          @{{ s.userId }} <span class="cs-mention-group">{{ s.seatGroup || '坐席' }}</span>
          <el-tag v-if="s.online === '1'" size="small" type="success">在线</el-tag>
        </div>
      </div>

      <el-input
        v-model="content"
        type="textarea"
        :rows="4"
        :disabled="disabled"
        :placeholder="messageMode === 'reply' ? '输入回复内容' : '输入内部备注,仅团队可见'"
        maxlength="2000"
        show-word-limit
      />
      <div class="cs-chat-actions">
        <el-button v-if="messageMode === 'reply'" type="primary" :loading="sending" :disabled="disabled" @click="submit">发送回复</el-button>
        <el-button v-else type="warning" :loading="noteSaving" :disabled="disabled" @click="submitNote">保存内部备注</el-button>
      </div>
    </div>

    <el-dialog v-model="preview.visible" :title="preview.name" width="520px">
      <el-image :src="preview.src" :alt="preview.name" fit="contain" style="width: 100%" />
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-attachments {
  margin: 8px 0;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}
.cs-attachments-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.cs-attachment-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cs-attachment {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 13px;
}
.cs-attachment:hover {
  color: var(--el-color-primary);
}
.cs-attachment-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cs-attachment-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-attachment-preview {
  max-width: 100%;
}
.cs-chat-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.cs-transcript {
  margin-bottom: 10px;
  padding: 8px;
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
}
.cs-transcript-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.cs-transcript-line {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.cs-transcript-speaker {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}
.cs-transcript-speaker.is-caller {
  color: var(--el-color-primary);
}
.cs-transcript-text.is-partial {
  color: var(--el-text-color-placeholder);
}
.cs-suggest {
  margin-bottom: 10px;
}
.cs-suggest-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.cs-suggest-group {
  margin-bottom: 8px;
}
.cs-suggest-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.cs-suggest-origin {
  font-size: 12px;
  color: var(--el-color-warning);
}
.cs-suggest-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  margin-bottom: 4px;
  cursor: pointer;
}
.cs-suggest-card:hover {
  border-color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}
.cs-suggest-tag {
  flex-shrink: 0;
}
.cs-suggest-content {
  flex: 1;
  font-size: 13px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.cs-suggest-score {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-suggest-more {
  display: flex;
  gap: 8px;
}
.cs-suggest-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.cs-chat-actions {
  margin-top: 10px;
  text-align: right;
}
.cs-compose {
  margin-top: 8px;
}
.cs-compose-tabs {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.cs-compose-note-tip,
.cs-compose-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-compose-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.cs-quick-panel,
.cs-mention-panel {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-bg-color);
  max-height: 180px;
  overflow-y: auto;
  margin-bottom: 6px;
}
.cs-quick-empty {
  padding: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-quick-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  cursor: pointer;
}
.cs-quick-item:hover {
  background: var(--el-fill-color-light);
}
.cs-quick-q {
  font-size: 12px;
  font-weight: 600;
}
.cs-quick-a {
  font-size: 12px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cs-mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  cursor: pointer;
  font-size: 13px;
}
.cs-mention-item:hover,
.cs-mention-item.is-active {
  background: var(--el-fill-color-light);
}
.cs-mention-group {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
