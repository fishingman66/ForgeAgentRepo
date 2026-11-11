<template>
  <el-drawer
    v-model="visible"
    title="配置调试"
    size="480"
    :close-on-click-modal="false"
    destroy-on-close
    class="debug-drawer"
    @close="handleClose"
  >
    <template #header>
      <div class="drawer-header">
        <span>配置调试</span>
        <el-tag size="small" type="warning" effect="plain">未保存草稿</el-tag>
      </div>
    </template>

    <div class="debug-body">
      <!-- 消息列表 -->
      <div ref="msgListRef" class="msg-list">
        <el-empty v-if="!messages.length" description="输入消息开始调试;调试基于当前表单未保存的配置" :image-size="80" />
        <div v-for="(m, i) in messages" :key="i" class="msg-item" :class="m.role">
          <div class="msg-bubble">
            <div v-if="m.role === 'user'" class="msg-text">{{ m.content }}</div>
            <div v-else class="msg-text md" v-html="renderMarkdown(m.content)"></div>
            <div v-if="m.typing" class="typing-dot"><span></span><span></span><span></span></div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          maxlength="2000"
          :disabled="sending"
          placeholder="输入调试消息,Enter 发送 / Shift+Enter 换行"
          @keydown.enter.exact="onEnterKey"
        />
        <div class="input-actions">
          <el-button size="small" text :disabled="!messages.length || sending" @click="handleReset">重置</el-button>
          <el-button size="small" text :disabled="!messages.length || sending" @click="handleGotoChat">在正式会话中打开</el-button>
          <span style="flex: 1"></span>
          <el-button size="small" type="primary" :loading="sending" :disabled="!input.trim()" @click="handleSend">发送</el-button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import MarkdownIt from 'markdown-it';
import { streamDebugChat } from '@/api/agent/session';
import type { AgentDraftForm } from '@/api/agent/session/types';
import { useRouter } from 'vue-router';

interface DebugMsg {
  role: 'user' | 'assistant';
  content: string;
  typing?: boolean;
}

const props = defineProps<{
  modelValue: boolean;
  /** 当前编辑表单的实时值(未保存也生效) */
  draft: AgentDraftForm | null;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
}>();

const router = useRouter();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
});

const md = new MarkdownIt({ html: false, linkify: true, breaks: true });
const renderMarkdown = (text: string) => md.render(text || '');

const messages = ref<DebugMsg[]>([]);
const input = ref('');
const sending = ref(false);
const debugSessionId = ref<string>('');
const msgListRef = ref<HTMLElement>();

const scrollToBottom = () => {
  nextTick(() => {
    const el = msgListRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
};

/** Enter 发送:输入法组合态(候选词确认)不拦截 */
const onEnterKey = (e: KeyboardEvent) => {
  if (e.isComposing) return;
  e.preventDefault();
  handleSend();
};

const handleSend = async () => {
  const content = input.value.trim();
  if (!content || sending.value) return;
  if (!props.draft?.agentId) {
    proxy?.$modal.msgWarning('请先保存 Agent 再进行调试');
    return;
  }
  input.value = '';
  messages.value.push({ role: 'user', content });
  const assistant: DebugMsg = { role: 'assistant', content: '', typing: true };
  messages.value.push(assistant);
  sending.value = true;
  scrollToBottom();

  const body: Parameters<typeof streamDebugChat>[0] = {
    content,
    agentDraft: props.draft
  };
  if (debugSessionId.value) body.debugSessionId = debugSessionId.value;

  await streamDebugChat(body, {
    onMeta: (sid) => {
      debugSessionId.value = String(sid);
    },
    onDelta: (d) => {
      assistant.content += d;
      scrollToBottom();
    },
    onDone: () => {
      assistant.typing = false;
      sending.value = false;
      scrollToBottom();
    },
    onError: (msg) => {
      assistant.typing = false;
      assistant.content = assistant.content || `⚠️ ${msg || '调试失败'}`;
      sending.value = false;
      proxy?.$modal.msgError(msg || '调试失败');
      scrollToBottom();
    }
  });
  // 兜底:流异常关闭时复位
  sending.value = false;
  assistant.typing = false;
};

const handleReset = () => {
  messages.value = [];
  debugSessionId.value = '';
};

const handleGotoChat = () => {
  if (!props.draft?.agentId) return;
  visible.value = false;
  router.push({ path: '/agent/chat', query: { agentId: String(props.draft.agentId) } });
};

const handleClose = () => {
  // 关闭即销毁本地状态(后端 30 分钟无活动自动清理)
  messages.value = [];
  debugSessionId.value = '';
  input.value = '';
};
</script>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.debug-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 12px;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.msg-item {
  display: flex;
}

.msg-item.user {
  justify-content: flex-end;
}

.msg-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.msg-item.user .msg-bubble {
  background: var(--el-color-primary-light-8);
  border-color: var(--el-color-primary-light-5);
}

.msg-text {
  white-space: pre-wrap;
}

.msg-text.md {
  white-space: normal;
}

.msg-text.md :deep(p) {
  margin: 4px 0;
}

.msg-text.md :deep(pre) {
  background: var(--el-fill-color-darker);
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
}

.msg-text.md :deep(code) {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}

.typing-dot {
  display: inline-flex;
  gap: 4px;
  padding: 4px 0;
}

.typing-dot span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
  animation: blink 1.2s infinite ease-in-out;
}

.typing-dot span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dot span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 80%, 100% { opacity: 0.3; }
  40% { opacity: 1; }
}

.input-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.input-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
