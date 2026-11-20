<script setup lang="ts">
/**
 * 渠道接入(C3):网页挂件 / 公众号 / 企微 / 语音渠道的配置管理。
 * 语音渠道(channel_type=voice_inbound)由 callcenter 种子数据维护,
 * 本页可查看与启用停用,回调验签密钥保存走加密通道。
 * 扩展点:挂件代码片段复制、渠道模板一键应用(applyTemplate)、渠道级模型路由。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  deleteAgentRoute,
  listAgentRoutes,
  listChannels,
  saveAgentRoute,
  saveChannel
} from '@/api/customerservice';
import type { CsAgentRouteRow, CsChannel } from '@/api/customerservice';

const rows = ref<CsChannel[]>([]);
const routes = ref<CsAgentRouteRow[]>([]);
const loading = ref(false);
const dialog = ref(false);
const editing = ref(false);

/** 新增路由表单：渠道编码留空 = 对该空间所有渠道生效。 */
const routeForm = reactive({ channelCode: '', intentCode: '', agentId: undefined as number | undefined, priority: 0 });

const form = reactive<CsChannel>({
  channelCode: '',
  channelType: 'widget',
  encodingAesKey: '',
  corpId: '',
  channelConfig: '',
  agentId: 1,
  ownerUserId: 1,
  knowledgeIds: '',
  fallbackReply: '抱歉，暂时没有找到相关答案，已为你转接人工客服。',
  handoffPolicy: 'zero_hit',
  sessionMode: 'continuous',
  channelSecret: '',
  corsAllowlist: '',
  rateLimit: 60,
  status: '0',
  widgetTheme: '#2563eb',
  widgetMode: 'bubble',
  widgetWelcome: '',
  widgetConfig: '',
  widgetLang: 'zh_CN'
});

const reset = () => {
  form.id = undefined;
  form.channelCode = '';
  form.channelType = 'widget';
  form.encodingAesKey = '';
  form.corpId = '';
  form.channelConfig = '';
  form.agentId = 1;
  form.ownerUserId = 1;
  form.knowledgeIds = '';
  form.fallbackReply = '抱歉，暂时没有找到相关答案，已为你转接人工客服。';
  form.handoffPolicy = 'zero_hit';
  form.sessionMode = 'continuous';
  form.channelSecret = '';
  form.corsAllowlist = '';
  form.rateLimit = 60;
  form.status = '0';
  form.widgetTheme = '#2563eb';
  form.widgetMode = 'bubble';
  form.widgetWelcome = '';
  form.widgetConfig = '';
  form.widgetLang = 'zh_CN';
};

const load = async () => {
  loading.value = true;
  try {
    const res = await listChannels();
    rows.value = res.data ?? [];
    const routeRes = await listAgentRoutes();
    routes.value = routeRes.data ?? [];
  } catch {
    ElMessage.error('渠道列表加载失败');
  } finally {
    loading.value = false;
  }
};

/** 新增路由(P3)：同空间同渠道同意图唯一，重复提交是更新。 */
const addRoute = async () => {
  if (!routeForm.intentCode.trim() || !routeForm.agentId) {
    ElMessage.warning('意图编码与 Agent ID 都要填');
    return;
  }
  try {
    await saveAgentRoute({
      channelCode: routeForm.channelCode.trim() || null,
      intentCode: routeForm.intentCode.trim().toUpperCase(),
      agentId: routeForm.agentId,
      priority: routeForm.priority,
      enabled: '1'
    });
    ElMessage.success('路由已保存');
    routeForm.intentCode = '';
    routeForm.agentId = undefined;
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const removeRoute = async (row: CsAgentRouteRow) => {
  try {
    await deleteAgentRoute(row.id);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败');
  }
};

const openCreate = () => {
  reset();
  editing.value = false;
  dialog.value = true;
};

const openEdit = (row: CsChannel) => {
  Object.assign(form, row);
  editing.value = true;
  dialog.value = true;
};

const submit = async () => {
  if (!form.channelCode.trim()) {
    ElMessage.warning('渠道编码不能为空');
    return;
  }
  try {
    await saveChannel({ ...form });
    ElMessage.success('渠道已保存');
    dialog.value = false;
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const closingSlash = '/';
const widgetSnippet = (code: string) =>
  `<script src="/cs-widget.js" data-channel="${code}"><${closingSlash}script>`;

const callbackUrl = (row: CsChannel) =>
  `/chat-channel/${row.channelType}/${row.channelCode}`;

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header>
        <div class="cs-header"><span>渠道接入</span><el-button type="primary" size="small" @click="openCreate">新增渠道</el-button></div>
      </template>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="channelCode" label="渠道编码" min-width="150" />
        <el-table-column prop="channelType" label="类型" width="120" />
        <el-table-column prop="agentId" label="Agent" width="100" />
        <el-table-column prop="sessionMode" label="会话模式" width="130" />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="回调地址" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row as CsChannel).channelType === 'email' ? 'IMAP 轮询(无回调地址)' : callbackUrl(row as CsChannel) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="openEdit((row as CsChannel))">编辑</el-button></template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="还没有渠道，先新增一个" :image-size="70" />
    </el-card>

    <el-card v-if="rows.length" shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>Agent 路由（意图 → 专家 Agent）</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <div class="cs-route-form">
        <el-input v-model="routeForm.channelCode" size="small" style="width: 160px" placeholder="渠道编码（留空=全渠道）" />
        <el-input v-model="routeForm.intentCode" size="small" style="width: 160px" placeholder="意图编码，如 RETURN_REFUND" />
        <el-input-number v-model="routeForm.agentId" size="small" :controls="false" style="width: 180px" placeholder="Agent ID" />
        <el-input-number v-model="routeForm.priority" size="small" style="width: 110px" />
        <el-button type="primary" size="small" @click="addRoute">保存路由</el-button>
      </div>
      <div class="cs-tip">
        只在会话首条消息生效：命中路由由该专家 Agent 接待整通会话；未命中回落渠道默认 Agent。
      </div>
      <el-table :data="routes" size="small">
        <el-table-column prop="channelCode" label="渠道" width="160">
          <template #default="{ row }">{{ row.channelCode || '全部渠道' }}</template>
        </el-table-column>
        <el-table-column prop="intentCode" label="意图" width="180" />
        <el-table-column prop="agentId" label="Agent ID" min-width="180" />
        <el-table-column prop="priority" label="优先级" width="100" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeRoute((row as CsAgentRouteRow))">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!routes.length" description="还没有路由，命中意图时仍用渠道默认 Agent" :image-size="70" />
    </el-card>

    <el-card v-if="rows.length" shadow="never" class="cs-card">
      <template #header>网页挂件嵌入代码</template>
      <div v-for="row in rows" :key="row.channelCode" class="cs-snippet">
        <span class="cs-code-label">{{ row.channelCode }}</span>
        <code>{{ widgetSnippet(row.channelCode) }}</code>
      </div>
      <div class="cs-tip">挂件主题、形态、开场消息在上方可视化配置，由后端 widget-config 端点下发给挂件；扩展配置 JSON 可设 position/size/allowedOrigins（宿主上下文桥白名单）。挂件为原生 JS，随 public 目录打包。</div>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑渠道' : '新增渠道'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="渠道编码"><el-input v-model="form.channelCode" :disabled="editing" placeholder="例如 web-official" /></el-form-item>
        <el-form-item label="渠道类型">
          <el-select v-model="form.channelType" style="width: 100%">
            <el-option label="网页挂件" value="widget" />
            <el-option label="微信公众号" value="wechat_mp" />
            <el-option label="企业微信" value="wecom" />
            <el-option label="独立 H5" value="h5" />
            <el-option label="开放 API" value="openapi" />
            <el-option label="邮件工单" value="email" />
          </el-select>
        </el-form-item>
        <el-form-item label="客服 Agent ID"><el-input-number v-model="form.agentId" :min="1" /></el-form-item>
        <el-form-item label="服务账号用户ID"><el-input-number v-model="form.ownerUserId" :min="1" /></el-form-item>
        <el-form-item label="知识库ID"><el-input v-model="form.knowledgeIds" placeholder="逗号分隔，留空则走兜底话术" /></el-form-item>
        <el-form-item label="兜底话术"><el-input v-model="form.fallbackReply" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="转人工策略">
          <el-select v-model="form.handoffPolicy" style="width: 100%">
            <el-option label="零命中自动转人工" value="zero_hit" />
            <el-option label="仅访客主动" value="manual" />
            <el-option label="关键词触发" value="keyword" />
          </el-select>
        </el-form-item>
        <el-form-item label="会话模式">
          <el-select v-model="form.sessionMode" style="width: 100%">
            <el-option label="连续会话" value="continuous" />
            <el-option label="每条消息独立会话" value="per_message" />
          </el-select>
        </el-form-item>
        <el-form-item label="回调验签密钥"><el-input v-model="form.channelSecret" placeholder="公众号/企微回调 token" /></el-form-item>
        <template v-if="form.channelType === 'wecom'">
          <el-form-item label="EncodingAESKey">
            <el-input v-model="form.encodingAesKey" placeholder="43 位;填写后强制走加密模式,留空表示不修改" />
            <div class="cs-tip">加密模式:企微后台「接收消息」选择安全模式后必填;配了即强制加密,不会回退明文</div>
          </el-form-item>
          <el-form-item label="CorpID">
            <el-input v-model="form.corpId" placeholder="ww 开头的企业 ID,用于密文 receiveid 校验" />
          </el-form-item>
        </template>
        <el-form-item v-if="form.channelType === 'email'" label="邮件配置JSON">
          <el-input
            v-model="form.channelConfig"
            type="textarea"
            :rows="5"
            placeholder='{"imapHost":"imap.example.com","imapPort":993,"imapUser":"support@example.com","imapPassword":"***","imapFolder":"INBOX","smtpHost":"smtp.example.com","smtpPort":465,"smtpUser":"support@example.com","smtpPassword":"***","fromAddress":"support@example.com","replyEnabled":true}'
          />
          <div class="cs-tip">含密码,整段加密存储且不回显;编辑时留空表示沿用原配置。轮询需在服务端开启 agent.customer-service.email-ingest-enabled</div>
        </el-form-item>
        <el-form-item label="限流(次/分)"><el-input-number v-model="form.rateLimit" :min="1" :max="1000" /></el-form-item>
        <el-form-item label="挂件主题色"><el-input v-model="form.widgetTheme" placeholder="#2563eb" /></el-form-item>
        <el-form-item label="挂件形态">
          <el-select v-model="form.widgetMode" style="width: 100%">
            <el-option label="气泡按钮" value="bubble" />
            <el-option label="侧边栏" value="sidebar" />
          </el-select>
        </el-form-item>
        <el-form-item label="开场消息"><el-input v-model="form.widgetWelcome" placeholder="留空则不展示" /></el-form-item>
        <el-form-item label="扩展配置JSON"><el-input v-model="form.widgetConfig" type="textarea" :rows="2" placeholder='{"position":"right","size":{"width":360,"height":520}}' /></el-form-item>
        <el-form-item label="挂件默认语言">
          <el-select v-model="form.widgetLang" style="width: 100%">
            <el-option label="简体中文" value="zh_CN" />
            <el-option label="English" value="en_US" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" value="0" />
            <el-option label="停用" value="1" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="submit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-card { margin-top: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-snippet { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; font-size: 12px; }
.cs-code-label { min-width: 120px; color: var(--el-text-color-secondary); }
.cs-snippet code { background: var(--el-fill-color-light); padding: 4px 8px; border-radius: 4px; word-break: break-all; }
.cs-tip { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 8px; }
.cs-route-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 4px; }
</style>
