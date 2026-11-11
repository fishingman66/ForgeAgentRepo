<script setup lang="ts">
/**
 * 合规管理(C4 + P0):审计日志 / 隐私擦除 / 留存到期清理 / 敏感词词库。
 * 擦除会级联清理文本域与语音域(通话/转写/录音,V2 起经事件联动),
 * 审计事件含 ERASE / RECORDING_ACCESS / RECORDING_PURGE / SENSITIVE_HIT。
 * 扩展点:录音留存与出域记录视图(RECORDING_EGRESS)、合规报表导出。
 */
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  applyTemplate,
  deleteSensitiveWord,
  listAudit,
  listSensitiveWords,
  listTemplates,
  purgeExpired,
  saveSensitiveWord
} from '@/api/customerservice';
import type { CsAuditLog, CsSensitiveWord, CsTemplate } from '@/api/customerservice';

const audits = ref<CsAuditLog[]>([]);
const templates = ref<CsTemplate[]>([]);
const words = ref<CsSensitiveWord[]>([]);
const newWord = ref('');
const newSeverity = ref('BLOCK');
const channelCode = ref('');
const loading = ref(false);
const purging = ref(false);

const load = async () => {
  loading.value = true;
  try {
    const [auditRes, templateRes, wordRes] = await Promise.all([
      listAudit(),
      listTemplates(),
      listSensitiveWords()
    ]);
    audits.value = auditRes.data ?? [];
    templates.value = templateRes.data ?? [];
    words.value = wordRes.data ?? [];
  } catch {
    ElMessage.error('合规数据加载失败');
  } finally {
    loading.value = false;
  }
};

/** 新增敏感词(P0):平台级词条只读,空间级可维护;后端保存后立即失效本地快照。 */
const addWord = async () => {
  const word = newWord.value.trim();
  if (!word) {
    ElMessage.warning('请输入词条');
    return;
  }
  try {
    await saveSensitiveWord({ word, severity: newSeverity.value, enabled: '1' });
    ElMessage.success('已添加');
    newWord.value = '';
    await load();
  } catch {
    ElMessage.error('添加失败');
  }
};

const removeWord = async (row: CsSensitiveWord) => {
  if (row.id == null) {
    return;
  }
  try {
    await deleteSensitiveWord(row.id);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败（平台级词条只读）');
  }
};

const apply = async (code: string) => {
  if (!channelCode.value.trim()) {
    ElMessage.warning('先填写要应用的渠道编码');
    return;
  }
  try {
    await applyTemplate(code, channelCode.value.trim());
    ElMessage.success('模板已应用');
    await load();
  } catch {
    ElMessage.error('应用失败，请检查渠道与发布门禁');
  }
};

const purge = async () => {
  purging.value = true;
  try {
    await purgeExpired();
    ElMessage.success('留存清理已执行');
    await load();
  } catch {
    ElMessage.error('清理失败');
  } finally {
    purging.value = false;
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-row :gutter="12">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>数据保护</template>
          <ul class="cs-list">
            <li>访客消息落库前做 PII 脱敏，覆盖手机号、身份证、银行卡与邮箱</li>
            <li>渠道回调密钥以 AES-GCM 加密存储，管理端不回显</li>
            <li>隐私擦除级联清理消息、工单、评价与质检数据并留审计</li>
            <li>留存到期自动清理，默认 180 天，每日 03:00 执行</li>
          </ul>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="cs-header"><span>客服模板</span><el-button size="small" @click="load">刷新</el-button></div>
          </template>
          <div class="cs-actions">
            <el-input v-model="channelCode" size="small" style="width: 220px" placeholder="目标渠道编码" />
            <span class="cs-tip">模板只写渠道级配置，不改动已发布 Agent</span>
          </div>
          <el-table v-loading="loading" :data="templates" size="small">
            <el-table-column prop="name" label="模板" width="130" />
            <el-table-column prop="handoffPolicy" label="转人工策略" width="130" />
            <el-table-column prop="fallbackReply" label="中文兜底" min-width="200" show-overflow-tooltip />
            <el-table-column prop="fallbackReplyEn" label="英文兜底" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="100">
              <template #default="{ row }"><el-button link type="primary" size="small" @click="apply(row.code)">应用</el-button></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header"><span>敏感词词库（P0）</span><el-button size="small" @click="load">刷新</el-button></div>
      </template>
      <div class="cs-actions">
        <el-input v-model="newWord" size="small" style="width: 200px" placeholder="新增词条" />
        <el-select v-model="newSeverity" size="small" style="width: 120px">
          <el-option label="拦截" value="BLOCK" />
          <el-option label="打码" value="MASK" />
          <el-option label="仅告警" value="WARN" />
        </el-select>
        <el-button type="primary" size="small" @click="addWord">添加</el-button>
        <span class="cs-tip">平台级词条只读；入站命中拦截时直接转人工，出站命中替换兜底文案</span>
      </div>
      <el-table v-loading="loading" :data="words" size="small">
        <el-table-column prop="word" label="词条" width="180" show-overflow-tooltip />
        <el-table-column prop="severity" label="处置" width="100" />
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column label="归属" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.workspaceId == null" size="small" type="info">平台级</el-tag>
            <span v-else>本空间</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.workspaceId != null" link type="danger" size="small" @click="removeWord((row as CsSensitiveWord))">删除</el-button>
            <span v-else class="cs-tip">只读</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header"><span>合规审计</span><el-button size="small" :loading="purging" @click="purge">执行留存清理</el-button></div>
      </template>
      <el-table v-loading="loading" :data="audits" size="small">
        <el-table-column prop="eventType" label="事件" width="180" />
        <el-table-column prop="actorType" label="操作方" width="110" />
        <el-table-column prop="actorId" label="操作者" width="150" />
        <el-table-column prop="channelCode" label="渠道" width="150" />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
      <el-empty v-if="!loading && !audits.length" description="暂无审计记录" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-card { margin-top: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-actions { margin-bottom: 10px; display: flex; align-items: center; gap: 12px; }
.cs-tip { font-size: 12px; color: var(--el-text-color-secondary); }
.cs-list { margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.9; color: var(--el-text-color-regular); }
</style>
