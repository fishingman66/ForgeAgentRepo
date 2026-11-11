<script setup lang="ts">
/**
 * 领域知识图谱维护(P3/M3 后续):客服空间的三元组登记、冲突盘点与实体盘点。
 *
 * 数据流:领域三元组落在空间共享级(user_id=0),由 forge-kg 统一归一化与 upsert;
 * 重复登记只累加权重,不产生重复边。冲突盘点把「同一实体对上说法相反」的关系
 * 两两一组列出,审核动作就是人工判断后删掉错的那条。
 * 检索侧(kg-expand-enabled)会用这些实体词扩展访客问题的搜索词。
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  addKgRelation,
  deleteKgRelation,
  listKgConflicts,
  listKgExpandMetrics,
  listKgNodes,
  listKgRelations
} from '@/api/customerservice';
import type { CsKgExpandMetricRow, CsKgNodeRow, CsKgRelationRow } from '@/api/customerservice';

const rows = ref<CsKgRelationRow[]>([]);
const conflictGroups = ref<CsKgRelationRow[][]>([]);
const nodes = ref<CsKgNodeRow[]>([]);
const expandMetrics = ref<CsKgExpandMetricRow[]>([]);
const loading = ref(false);
const dialog = ref(false);

/** 检索扩展效果:扩展侧与未扩展侧的零命中率(观测口径,非 A/B 实验) */
const metricRows = computed(() =>
  expandMetrics.value.map((m) => ({
    ...m,
    zeroRateAll: m.totalSearches ? ((m.zeroHitTotal / m.totalSearches) * 100).toFixed(1) + '%' : '—',
    zeroRateExpanded: m.expandedSearches
      ? ((m.zeroHitExpanded / m.expandedSearches) * 100).toFixed(1) + '%'
      : '—',
    zeroRatePlain:
      m.totalSearches - m.expandedSearches > 0
        ? (((m.zeroHitTotal - m.zeroHitExpanded) / (m.totalSearches - m.expandedSearches)) * 100).toFixed(1) + '%'
        : '—'
  }))
);

const blank = () => ({
  subject: '',
  relation: '',
  object: '',
  evidenceRef: undefined as number | undefined
});

const form = reactive(blank());

const load = async () => {
  loading.value = true;
  try {
    const [relations, conflicts, nodeRows, metrics] = await Promise.all([
      listKgRelations(),
      listKgConflicts(),
      listKgNodes(),
      listKgExpandMetrics()
    ]);
    rows.value = relations.data ?? [];
    conflictGroups.value = conflicts.data ?? [];
    nodes.value = nodeRows.data ?? [];
    expandMetrics.value = metrics.data ?? [];
  } catch {
    ElMessage.error('图谱数据加载失败');
  } finally {
    loading.value = false;
  }
};

const submit = async () => {
  if (!form.subject.trim() || !form.relation.trim() || !form.object.trim()) {
    ElMessage.warning('主语、关系、宾语都不能为空');
    return;
  }
  try {
    await addKgRelation({
      subject: form.subject.trim(),
      relation: form.relation.trim(),
      object: form.object.trim(),
      evidenceRef: form.evidenceRef
    });
    ElMessage.success('已登记(重复登记只累加权重)');
    dialog.value = false;
    Object.assign(form, blank());
    await load();
  } catch {
    ElMessage.error('登记失败');
  }
};

const remove = async (row: CsKgRelationRow) => {
  try {
    await ElMessageBox.confirm(
      `确认删除「${row.subject} —${row.relation}→ ${row.object}」？只删这条关系,两端实体保留。`,
      '删除确认',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await deleteKgRelation(row.id as number);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败');
  }
};

const nodeTags = computed(() =>
  nodes.value.map((n) => `${n.name}(${n.salience ?? 0})`)
);

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header>
        <div class="cs-header">
          <span>领域知识图谱</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <div class="cs-actions">
        <el-button type="primary" size="small" @click="dialog = true">登记三元组</el-button>
        <span class="cs-hint">
          主语—关系→宾语,落在空间共享级;开启 kg-expand 后,访客问题命中的实体会用它的邻接实体扩展检索词
        </span>
      </div>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column label="关系" min-width="260">
          <template #default="{ row }">
            <span class="kg-triple">{{ row.subject }} —{{ row.relation }}→ {{ row.object }}</span>
          </template>
        </el-table-column>
        <el-table-column label="权重" width="90">
          <template #default="{ row }">{{ row.weight ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="冲突" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.conflict" size="small" type="danger">说法相反</el-tag>
            <span v-else class="cs-hint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="remove((row as CsKgRelationRow))">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="kg-card">
      <template #header>
        <div class="cs-header">
          <span>冲突待审核</span>
          <span class="cs-hint">同一实体上说法相反的关系,人工判断后删掉错的那条</span>
        </div>
      </template>
      <el-empty v-if="conflictGroups.length === 0" description="暂无冲突" :image-size="60" />
      <div v-for="(group, gi) in conflictGroups" :key="gi" class="kg-conflict">
        <div v-for="row in group" :key="row.id" class="kg-conflict-row">
          <span class="kg-triple">{{ row.subject }} —{{ row.relation }}→ {{ row.object }}</span>
          <span class="cs-hint">权重 {{ row.weight ?? '—' }}</span>
          <el-button link type="danger" size="small" @click="remove(row)">删除这条</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="kg-card">
      <template #header>
        <div class="cs-header">
          <span>检索扩展效果(近 7 天)</span>
          <span class="cs-hint">观测口径:开启 kg-expand 后,命中实体的检索会带扩展词;扩展侧零命中率应低于未扩展侧</span>
        </div>
      </template>
      <el-empty v-if="metricRows.length === 0" description="暂无数据,需开启 agent.customer-service.kg-expand-enabled 并产生检索" :image-size="60" />
      <el-table v-else :data="metricRows" size="small">
        <el-table-column label="日期" prop="statDate" width="110" />
        <el-table-column label="总检索" prop="totalSearches" width="90" align="center" />
        <el-table-column label="带扩展词" prop="expandedSearches" width="100" align="center" />
        <el-table-column label="零命中(全部)" prop="zeroRateAll" width="120" align="center" />
        <el-table-column label="零命中(未扩展)" prop="zeroRatePlain" width="130" align="center" />
        <el-table-column label="零命中(扩展侧)" prop="zeroRateExpanded" width="130" align="center" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="kg-card">
      <template #header>
        <div class="cs-header">
          <span>实体盘点</span>
          <span class="cs-hint">按显著度排序,括号内为出现次数</span>
        </div>
      </template>
      <el-empty v-if="nodeTags.length === 0" description="还没有实体" :image-size="60" />
      <div v-else class="kg-nodes">
        <el-tag v-for="tag in nodeTags" :key="tag" size="small" class="kg-node-tag">{{ tag }}</el-tag>
      </div>
    </el-card>

    <el-dialog v-model="dialog" title="登记三元组" width="520px">
      <el-form label-width="96px">
        <el-form-item label="主语实体">
          <el-input v-model="form.subject" maxlength="64" placeholder="如：退款流程" />
        </el-form-item>
        <el-form-item label="关系">
          <el-input v-model="form.relation" maxlength="32" placeholder="动词短语，如：属于/需要/解决" />
        </el-form-item>
        <el-form-item label="宾语实体">
          <el-input v-model="form.object" maxlength="64" placeholder="如：售后领域" />
        </el-form-item>
        <el-form-item label="溯源引用">
          <el-input-number v-model="form.evidenceRef" :min="1" :controls="false" placeholder="FAQ/工单 ID，可空" />
          <span class="cs-hint">可空;便于以后追溯这条知识从哪来</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-page {
  padding: 12px;
}
.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cs-actions {
  margin-bottom: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.cs-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.kg-card {
  margin-top: 12px;
}
.kg-triple {
  font-family: monospace;
}
.kg-conflict {
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.kg-conflict:last-child {
  border-bottom: none;
}
.kg-conflict-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 2px 0;
}
.kg-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.kg-node-tag {
  margin: 0;
}
</style>
