<script setup lang="ts">
/**
 * FAQ 知识管理(C2):已审核答案直命中 + 零命中问题回收(数据飞轮)。
 * 流转:zero_hit 记录 → fromZeroHit 一键成稿 → audit 审核生效 → 到期 expire。
 * 扩展点:批量导入导出、答案多语种变体、生效时间区间可视化。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  auditAnswer,
  createGapAnswer,
  estimateFaqAnswers,
  expireAnswers,
  fromZeroHit,
  generateFaqAnswers,
  ignoreGap,
  listAnswers,
  listKnowledgeGaps,
  saveAnswer
} from '@/api/customerservice';
import type { CsKnowledgeAnswer, CsKnowledgeGapRow } from '@/api/customerservice';

const rows = ref<CsKnowledgeAnswer[]>([]);
const gaps = ref<CsKnowledgeGapRow[]>([]);
const statusFilter = ref('');
const dialog = ref(false);
const loading = ref(false);
const generating = ref(false);
const form = reactive({ question: '', answer: '' });

const load = async () => {
  loading.value = true;
  try {
    const [answerRes, gapRes] = await Promise.all([
      listAnswers(statusFilter.value || undefined),
      listKnowledgeGaps('OPEN', 30)
    ]);
    rows.value = answerRes.data ?? [];
    gaps.value = gapRes.data ?? [];
  } catch {
    ElMessage.error('答案列表加载失败');
  } finally {
    loading.value = false;
  }
};

/** 缺口转待审答案(P1):问题即缺口原文,答案在这张表里补。 */
const createFromGap = async (gap: CsKnowledgeGapRow) => {
  try {
    await createGapAnswer(gap.id);
    ElMessage.success('已生成待审答案，请在下方补写答案');
    await load();
  } catch {
    ElMessage.error('生成失败');
  }
};

const dropGap = async (gap: CsKnowledgeGapRow) => {
  try {
    await ignoreGap(gap.id);
    ElMessage.success('已忽略该缺口');
    await load();
  } catch {
    ElMessage.error('操作失败');
  }
};

const submit = async () => {
  if (!form.question.trim() || !form.answer.trim()) {
    ElMessage.warning('问题与答案不能为空');
    return;
  }
  try {
    await saveAnswer({ question: form.question, answer: form.answer, source: 'manual', status: 'PENDING', effectiveFrom: null, effectiveTo: null });
    ElMessage.success('已提交待审');
    dialog.value = false;
    form.question = '';
    form.answer = '';
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const audit = async (id: number, status: string) => {
  try {
    await auditAnswer(id, status);
    ElMessage.success(status === 'ACTIVE' ? '已生效' : '已驳回');
    await load();
  } catch {
    ElMessage.error('审核失败');
  }
};

const generate = async () => {
  try {
    const res = await fromZeroHit(20);
    ElMessage.success(`已生成 ${res.data ?? 0} 条待审答案`);
    await load();
  } catch {
    ElMessage.error('生成失败');
  }
};

/** B3 AI 生成候选答案:仍 PENDING,需人工审核;低置信度自动跳过。 */
const aiGenerate = async () => {
  generating.value = true;
  try {
    const res = await generateFaqAnswers(20);
    const data = (res as unknown as { data?: { candidates: number; generated: number; skipped: number; estimatedCost: string | null } })?.data;
    if (data) {
      ElMessage.success(`候选 ${data.candidates} 条,已生成 ${data.generated} 条,低分跳过 ${data.skipped} 条(均待审核)`);
    } else {
      ElMessage.success('已触发 AI 候选生成,请刷新查看');
    }
    await load();
  } catch (e) {
    ElMessage.error((e as { msg?: string })?.msg || '生成失败,请确认已配置启用渠道与 Agent');
  } finally {
    generating.value = false;
  }
};

/** B3 成本估算:先看预估再决定是否生成。 */
const aiEstimate = async () => {
  try {
    const res = await estimateFaqAnswers(20);
    const data = (res as unknown as { data?: { candidates: number; estimatedCost: string | null } })?.data;
    const cost = data?.estimatedCost ? `,预估费用 ${data.estimatedCost}` : ',未配置单价';
    ElMessage.info(`待生成候选 ${data?.candidates ?? 0} 条${cost}`);
  } catch {
    ElMessage.error('估算失败');
  }
};

const expire = async () => {
  try {
    await expireAnswers();
    ElMessage.success('已清理过期答案');
    await load();
  } catch {
    ElMessage.error('清理失败');
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header><div class="cs-header"><span>FAQ 与补录答案</span><el-button size="small" @click="load">刷新</el-button></div></template>
      <div class="cs-actions">
        <el-button type="primary" size="small" @click="dialog = true">新增答案</el-button>
        <el-button size="small" @click="generate">从零命中生成</el-button>
        <el-button size="small" type="success" :loading="generating" @click="aiGenerate">AI 生成候选</el-button>
        <el-button size="small" @click="aiEstimate">成本估算</el-button>
        <el-button size="small" @click="expire">清理过期</el-button>
        <el-select v-model="statusFilter" size="small" style="width: 140px" @change="load">
          <el-option label="全部" value="" />
          <el-option label="待审" value="PENDING" />
          <el-option label="生效" value="ACTIVE" />
          <el-option label="驳回" value="REJECTED" />
          <el-option label="过期" value="EXPIRED" />
        </el-select>
      </div>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="answer" label="答案" min-width="240" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" width="110" />
        <el-table-column label="AI 候选" width="180">
          <template #default="{ row }">
            <template v-if="row.source === 'ai_generated'">
              <el-tag size="small" type="info">{{ row.confidence != null ? Number(row.confidence).toFixed(2) : '-' }}</el-tag>
              <span class="cs-cite" :title="row.citations || ''">{{ (row.citations || '[]').slice(0, 40) }}</span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="effectiveFrom" label="生效时间" width="170" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" @click="audit(row.id, 'ACTIVE')">通过</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="danger" size="small" @click="audit(row.id, 'REJECTED')">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header">
          <span>知识缺口（命中次数越高优先级越高）</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="gaps" size="small">
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column prop="question" label="未答问题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="140" />
        <el-table-column prop="hitCount" label="命中" width="80" />
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="createFromGap((row as CsKnowledgeGapRow))">生成待审答案</el-button>
            <el-button link type="info" size="small" @click="dropGap((row as CsKnowledgeGapRow))">忽略</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!gaps.length" description="暂无知识缺口" :image-size="70" />
    </el-card>

    <el-dialog v-model="dialog" title="新增答案" width="520px">
      <el-form label-width="70px">
        <el-form-item label="问题"><el-input v-model="form.question" placeholder="访客常见问题" /></el-form-item>
        <el-form-item label="答案"><el-input v-model="form.answer" type="textarea" :rows="4" placeholder="审核通过后才会对访客生效" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="submit">提交待审</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-card { margin-top: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-actions { margin-bottom: 10px; display: flex; flex-wrap: wrap; gap: 8px; }
.cs-cite { margin-left: 6px; font-size: 11px; color: var(--el-text-color-secondary); }
</style>
