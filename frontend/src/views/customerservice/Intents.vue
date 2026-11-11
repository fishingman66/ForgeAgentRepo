<script setup lang="ts">
/**
 * 意图管理(A3):规则打标的人工配置面。
 *
 * 数据流:listIntents 拉「平台级 + 本空间」意图,规则按 priority 升序在推荐链路里匹配,
 * 命中后给推荐记录打 intent 标签、把 kbIds 并入检索范围并对命中内容加分;
 * 多轮兜底只看最近 3 轮,所以运营配「退货」这类关键词时不必写满同义句。
 * 平台级意图(workspaceId 为空)对所有空间生效、只读——单个空间改不了。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { deleteIntent, listIntents, saveIntent } from '@/api/customerservice';
import type { CsIntent } from '@/api/customerservice';

const rows = ref<CsIntent[]>([]);
const loading = ref(false);
const dialog = ref(false);
const editing = ref(false);

const blank = (): CsIntent => ({
  intentCode: '',
  intentName: '',
  keywords: '',
  ruleType: 'KEYWORD',
  kbIds: '',
  priority: 100,
  enabled: '1'
});

const form = reactive<CsIntent>(blank());

const load = async () => {
  loading.value = true;
  try {
    const res = await listIntents();
    rows.value = res.data ?? [];
  } catch {
    ElMessage.error('意图列表加载失败');
  } finally {
    loading.value = false;
  }
};

const openCreate = () => {
  editing.value = false;
  Object.assign(form, blank());
  dialog.value = true;
};

const openEdit = (row: CsIntent) => {
  editing.value = true;
  Object.assign(form, blank(), row);
  dialog.value = true;
};

const submit = async () => {
  if (!form.intentCode.trim() || !form.intentName.trim() || !form.keywords.trim()) {
    ElMessage.warning('编码、名称、关键词都不能为空');
    return;
  }
  try {
    await saveIntent({ ...form, intentCode: form.intentCode.trim(), keywords: form.keywords.trim() });
    ElMessage.success(editing.value ? '已保存' : '已新增');
    dialog.value = false;
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const remove = async (row: CsIntent) => {
  try {
    await ElMessageBox.confirm(`确认删除意图「${row.intentName}」？历史推荐上的意图标签不受影响。`, '删除确认', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await deleteIntent(row.id as number);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败');
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header>
        <div class="cs-header">
          <span>意图标签</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <div class="cs-actions">
        <el-button type="primary" size="small" @click="openCreate">新增意图</el-button>
        <span class="cs-hint">匹配顺序按优先级从小到大；关键词逗号分隔，任一命中即打标</span>
      </div>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="intentCode" label="编码" width="150" show-overflow-tooltip />
        <el-table-column prop="intentName" label="名称" width="120" />
        <el-table-column prop="keywords" label="关键词" min-width="220" show-overflow-tooltip />
        <el-table-column prop="kbIds" label="关联知识库" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.kbIds || '—' }}</template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" />
        <el-table-column prop="ruleType" label="规则" width="90" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled === '1' ? 'success' : 'info'">
              {{ row.enabled === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="归属" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.workspaceId == null" size="small" type="info">平台级</el-tag>
            <span v-else>本空间</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button
              v-if="row.workspaceId != null"
              link
              type="primary"
              size="small"
              @click="openEdit((row as CsIntent))"
            >编辑</el-button>
            <el-button
              v-if="row.workspaceId != null"
              link
              type="danger"
              size="small"
              @click="remove((row as CsIntent))"
            >删除</el-button>
            <span v-if="row.workspaceId == null" class="cs-hint">只读</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑意图' : '新增意图'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="意图编码">
          <el-input v-model="form.intentCode" :disabled="editing" placeholder="如 RETURN_REFUND，落推荐记录用于聚合" />
        </el-form-item>
        <el-form-item label="意图名称">
          <el-input v-model="form.intentName" placeholder="坐席卡片上展示的名称" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="form.keywords"
            type="textarea"
            :rows="2"
            placeholder="逗号分隔，如：退货,退款,换货"
          />
        </el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="form.ruleType" style="width: 160px">
            <el-option label="关键词" value="KEYWORD" />
            <el-option label="正则" value="REGEX" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联知识库">
          <el-input v-model="form.kbIds" placeholder="知识库ID，逗号分隔；命中后并入检索范围" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="9999" />
          <span class="cs-hint">数值越小越先匹配</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">{{ editing ? '保存' : '新增' }}</el-button>
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
</style>
