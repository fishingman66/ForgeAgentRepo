<script setup lang="ts">
/**
 * 坐席辅助看板(A5):采纳率、意图覆盖、动作执行与零采纳回流。
 *
 * 口径(用户 2026-11-10 明确):采纳率 = 采纳条数 / 推荐条数,按推荐条数算;
 * 一条推荐上先回复再标记解决,仍只算一条采纳,动作次数单列展示。
 * 零采纳回流:同会话最近 3 条推荐全部未采纳且超期,用触发原文生成待审 FAQ,
 * 运营在「知识答案」页补答案并审核生效——回流只做一次,同问题去重。
 */
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  adoptImprovementEval,
  adoptImprovementKb,
  assistCost,
  assistMetrics,
  assistQuota,
  clearSemanticCache,
  exportEvalCases,
  ignoreDeadLetter,
  ignoreImprovement,
  listAssistBadcases,
  listDeadLetters,
  listImprovements,
  listSemanticCache,
  rebuildAssistCost,
  rebuildAssistMetrics,
  recycleZeroAdopt,
  replayDeadLetter
} from '@/api/customerservice';
import type {
  CsAssistOverview,
  CsBadcaseRow,
  CsCostOverview,
  CsDeadLetterRow,
  CsQuotaView,
  CsSeatSuggestion,
  CsSemanticCacheRow
} from '@/api/customerservice';

const overview = ref<CsAssistOverview | null>(null);
const badcases = ref<CsSeatSuggestion[]>([]);
const improvements = ref<CsBadcaseRow[]>([]);
const cacheRows = ref<CsSemanticCacheRow[]>([]);
const cost = ref<CsCostOverview | null>(null);
const quota = ref<CsQuotaView | null>(null);
const deadLetters = ref<CsDeadLetterRow[]>([]);
const cacheChannel = ref('');
const loading = ref(false);
const recycling = ref(false);
const days = ref(7);

const cards = [
  { key: 'suggestionCount', label: '推荐条数', suffix: '' },
  { key: 'adoptedCount', label: '采纳条数', suffix: '' },
  { key: 'adoptRate', label: '采纳率', suffix: ' %' },
  { key: 'actionCount', label: '动作执行', suffix: '' },
  { key: 'intentRate', label: '意图覆盖', suffix: ' %' },
  { key: 'recycledCount', label: '回流 FAQ', suffix: '' }
];

const load = async () => {
  loading.value = true;
  try {
    const res = await assistMetrics(days.value);
    overview.value = res.data ?? null;
    const bad = await listAssistBadcases(20);
    badcases.value = bad.data ?? [];
    const imp = await listImprovements('PENDING', 30);
    improvements.value = imp.data ?? [];
    const costRes = await assistCost(days.value);
    cost.value = costRes.data ?? null;
    const quotaRes = await assistQuota();
    quota.value = quotaRes.data ?? null;
    const deadRes = await listDeadLetters('PENDING', 30);
    deadLetters.value = deadRes.data ?? [];
  } catch {
    ElMessage.error('辅助指标加载失败');
  } finally {
    loading.value = false;
  }
};

/** 成本按「客服会话」聚合 token；金额只在配了单价时才有值。 */const rebuildCost = async () => {
  try {
    await rebuildAssistCost();
    ElMessage.success('今日成本已重算');
    await load();
  } catch {
    ElMessage.error('重算失败');
  }
};

/** 死信重投：摘要类可补跑，附件类只能标记不可重投（字节已丢）。 */
const replay = async (row: CsDeadLetterRow) => {
  try {
    await replayDeadLetter(row.id);
    ElMessage.success('已重投');
    await load();
  } catch {
    ElMessage.warning('重投失败：该类型可能不支持自动重投，或重投仍然失败');
  }
};

const dropDeadLetter = async (row: CsDeadLetterRow) => {
  try {
    await ignoreDeadLetter(row.id);
    ElMessage.success('已忽略');
    await load();
  } catch {
    ElMessage.error('操作失败');
  }
};

/** 语义缓存按「空间 + 渠道」分区查看，查看前先填渠道编码。 */
const loadCache = async () => {
  const channel = cacheChannel.value.trim();
  if (!channel) {
    ElMessage.warning('先填写渠道编码');
    return;
  }
  try {
    const res = await listSemanticCache(channel, 50);
    cacheRows.value = res.data ?? [];
  } catch {
    ElMessage.error('缓存加载失败');
  }
};

const clearCache = async () => {
  const channel = cacheChannel.value.trim();
  if (!channel) {
    return;
  }
  try {
    const res = await clearSemanticCache(channel);
    ElMessage.success(`已清空 ${res.data ?? 0} 条缓存`);
    await loadCache();
  } catch {
    ElMessage.error('清空失败');
  }
};

/** 改进单三个出口：采纳为知识 / 标记评测 / 忽略。 */
const adoptKb = async (row: CsBadcaseRow) => {
  try {
    await adoptImprovementKb(row.id);
    ElMessage.success('已生成待审答案');
    await load();
  } catch {
    ElMessage.error('操作失败（该 badcase 可能没有问题文本）');
  }
};

const adoptEval = async (row: CsBadcaseRow) => {
  try {
    await adoptImprovementEval(row.id);
    ElMessage.success('已标记为评测用例');
    await load();
  } catch {
    ElMessage.error('操作失败');
  }
};

const dropImprovement = async (row: CsBadcaseRow) => {
  try {
    await ignoreImprovement(row.id, '运营忽略');
    ElMessage.success('已忽略');
    await load();
  } catch {
    ElMessage.error('操作失败');
  }
};

const exportEval = async () => {
  try {
    const res = await exportEvalCases(200);
    const payload = res.data ?? [];
    ElMessage.success(`已导出 ${payload.length} 条（可从控制台复制）`);
    // 不落文件：评测集导入在平台侧，这里只把标准结构吐出来
    console.info('[eval-export]', JSON.stringify(payload));
    await load();
  } catch {
    ElMessage.error('导出失败');
  }
};

const rebuild = async () => {
  try {
    await rebuildAssistMetrics();
    ElMessage.success('今日指标已重建');
    await load();
  } catch {
    ElMessage.error('重建失败');
  }
};

const recycle = async () => {
  recycling.value = true;
  try {
    const res = await recycleZeroAdopt(24, 50);
    const data = res.data ?? {};
    ElMessage.success(
      `扫描 ${data.scanned ?? 0} 条，回流 ${data.recycled ?? 0} 条，新建待审 FAQ ${data.created ?? 0} 条`
    );
    await load();
  } catch {
    ElMessage.error('回流失败');
  } finally {
    recycling.value = false;
  }
};

const total = () => overview.value?.total as unknown as Record<string, number> | undefined;

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-row :gutter="12">
      <el-col v-for="item in cards" :key="item.key" :span="4">
        <el-card shadow="never">
          <div class="cs-label">{{ item.label }}</div>
          <div class="cs-value">{{ total()?.[item.key] ?? 0 }}{{ item.suffix }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>日指标 · 采纳率按推荐条数算</span>
          <div class="cs-actions">
            <el-select v-model="days" size="small" style="width: 110px" @change="load">
              <el-option label="近 7 天" :value="7" />
              <el-option label="近 14 天" :value="14" />
              <el-option label="近 30 天" :value="30" />
            </el-select>
            <el-button size="small" @click="rebuild">重建今日</el-button>
            <el-button type="warning" size="small" :loading="recycling" @click="recycle">
              零采纳回流
            </el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="loading" :data="overview?.days ?? []" size="small">
        <el-table-column prop="statDate" label="日期" width="170" />
        <el-table-column prop="suggestionCount" label="推荐条数" width="110" />
        <el-table-column prop="adoptedCount" label="采纳条数" width="110" />
        <el-table-column prop="actionCount" label="动作执行" width="110" />
        <el-table-column prop="intentCount" label="带意图" width="100" />
        <el-table-column prop="recycledCount" label="回流FAQ" width="110" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header><span>低质推荐追溯（未采纳，带触发原文）</span></template>
      <el-table :data="badcases" size="small">
        <el-table-column prop="triggerText" label="触发原文" min-width="240" show-overflow-tooltip />
        <el-table-column prop="intent" label="意图" width="140" />
        <el-table-column prop="source" label="来源" width="90" />
        <el-table-column prop="adoptAction" label="埋点" width="180" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
      <el-empty v-if="!badcases.length" description="暂无非采纳推荐" :image-size="70" />
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>会话成本（token 口径）</span>
          <div class="cs-actions">
            <el-tag
              v-if="quota"
              size="small"
              :type="quota.level === 'BLOCK' ? 'danger' : (quota.level === 'WARN' ? 'warning' : 'success')"
            >
              配额 {{ quota.enabled ? `${quota.used} / ${quota.limit}` : '未启用' }}
            </el-tag>
            <el-button size="small" @click="rebuildCost">重算今日</el-button>
          </div>
        </div>
      </template>
      <el-row :gutter="12">
        <el-col :span="6">
          <div class="cs-label">总 token</div>
          <div class="cs-value">{{ cost?.total.totalTokens ?? 0 }}</div>
        </el-col>
        <el-col :span="6">
          <div class="cs-label">每会话 token</div>
          <div class="cs-value">{{ cost?.total.tokensPerConversation ?? 0 }}</div>
        </el-col>
        <el-col :span="6">
          <div class="cs-label">每次解决 token</div>
          <div class="cs-value">{{ cost?.total.tokensPerResolved ?? 0 }}</div>
        </el-col>
        <el-col :span="6">
          <div class="cs-label">参考金额</div>
          <div class="cs-value">
            {{ cost?.total.amountAvailable ? `${cost?.total.costAmount} ${cost?.total.currency}` : '—' }}
          </div>
        </el-col>
      </el-row>
      <el-alert v-if="cost" :title="cost.priceNote" type="info" :closable="false" class="cs-note" />
      <el-table :data="cost?.days ?? []" size="small">
        <el-table-column prop="statDate" label="日期" width="170" />
        <el-table-column prop="conversationCount" label="会话" width="90" />
        <el-table-column prop="callCount" label="调用" width="90" />
        <el-table-column prop="totalTokens" label="token" width="130" />
        <el-table-column prop="resolvedCount" label="结案" width="90" />
        <el-table-column prop="costAmount" label="金额" width="140" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>语义缓存（只缓存白名单意图的通用问答）</span>
          <div class="cs-actions">
            <el-input v-model="cacheChannel" size="small" style="width: 160px" placeholder="渠道编码" />
            <el-button size="small" @click="loadCache">查看</el-button>
            <el-button size="small" type="danger" plain @click="clearCache">清空该渠道</el-button>
          </div>
        </div>
      </template>
      <el-table :data="cacheRows" size="small">
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="answer" label="答案" min-width="240" show-overflow-tooltip />
        <el-table-column prop="intent" label="意图" width="140" />
        <el-table-column prop="hits" label="命中" width="80" />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
      </el-table>
      <el-empty v-if="!cacheRows.length" description="填渠道编码后查看" :image-size="70" />
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>改进单（待处理 badcase）</span>
          <div class="cs-actions">
            <el-button size="small" @click="exportEval">导出评测样本</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="improvements" size="small">
        <el-table-column prop="source" label="来源" width="140" />
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="answer" label="原答案" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column label="处置" width="230">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="adoptKb((row as CsBadcaseRow))">采纳为知识</el-button>
            <el-button link type="warning" size="small" @click="adoptEval((row as CsBadcaseRow))">标记评测</el-button>
            <el-button link type="info" size="small" @click="dropImprovement((row as CsBadcaseRow))">忽略</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!improvements.length" description="暂无待处理改进单" :image-size="70" />
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>死信（失败待补跑）</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="deadLetters" size="small">
        <el-table-column prop="eventType" label="类型" width="180" />
        <el-table-column prop="error" label="失败原因" min-width="260" show-overflow-tooltip />
        <el-table-column prop="retryCount" label="重投次数" width="100" />
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="replay((row as CsDeadLetterRow))">重投</el-button>
            <el-button link type="info" size="small" @click="dropDeadLetter((row as CsDeadLetterRow))">忽略</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!deadLetters.length" description="没有待补跑的失败" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.cs-page {
  padding: 12px;
}
.cs-card {
  margin-top: 12px;
}
.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cs-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.cs-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-value {
  font-size: 22px;
  font-weight: 500;
  margin-top: 6px;
}
.cs-note {
  margin: 10px 0;
}
</style>
