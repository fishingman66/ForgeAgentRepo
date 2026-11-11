<script setup lang="ts">
/**
 * 质检看板(C2):规则维护 + 质检结果复审。
 * 规则(REGEX / 关键词)由 listQualityRules / saveQualityRule 管理,
 * 结果由坐席回复与语音转写(callcenter source=VOICE_TRANSCRIPT)自动产生,
 * review 接口人工复核。扩展点:LLM 档质检、违规片段高亮定位。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listQualityChecks, listQualityRules, reviewQuality, saveQualityRule } from '@/api/customerservice';
import type { CsQualityCheck, CsQualityRule } from '@/api/customerservice';

const tab = ref('rules');
const rules = ref<CsQualityRule[]>([]);
const checks = ref<CsQualityCheck[]>([]);
const dialog = ref(false);
const loading = ref(false);
const form = reactive<CsQualityRule>({
  ruleType: 'KEYWORD', pattern: '', scoreDelta: -10, severity: 'WARN', enabled: '1'
});

const load = async () => {
  loading.value = true;
  try {
    const [ruleRes, checkRes] = await Promise.all([listQualityRules(), listQualityChecks()]);
    rules.value = ruleRes.data ?? [];
    checks.value = checkRes.data ?? [];
  } catch {
    ElMessage.error('质检数据加载失败');
  } finally {
    loading.value = false;
  }
};

const submit = async () => {
  if (!form.pattern.trim()) {
    ElMessage.warning('规则内容不能为空');
    return;
  }
  try {
    await saveQualityRule({ ...form });
    ElMessage.success('规则已保存');
    dialog.value = false;
    form.pattern = '';
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const review = async (id: number, status: string) => {
  try {
    await reviewQuality(id, status);
    ElMessage.success('已复核');
    await load();
  } catch {
    ElMessage.error('复核失败');
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header>
        <div class="cs-header"><span>会话质检</span><el-button size="small" @click="load">刷新</el-button></div>
      </template>
      <el-tabs v-model="tab">
        <el-tab-pane label="质检规则" name="rules">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="dialog = true">新增规则</el-button>
            <span class="cs-tip">平台级规则对所有空间生效，规则改动无需发版</span>
          </div>
          <el-table v-loading="loading" :data="rules" size="small">
            <el-table-column prop="pattern" label="规则内容" min-width="180" />
            <el-table-column prop="ruleType" label="类型" width="110" />
            <el-table-column prop="scoreDelta" label="扣分" width="90" />
            <el-table-column prop="severity" label="级别" width="100" />
            <el-table-column prop="enabled" label="启用" width="90" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="质检结果" name="checks">
          <el-table v-loading="loading" :data="checks" size="small">
            <el-table-column prop="ticketId" label="工单ID" min-width="170" />
            <el-table-column prop="score" label="得分" width="90" />
            <el-table-column prop="hitRules" label="命中规则" min-width="180" show-overflow-tooltip />
            <el-table-column prop="reviewStatus" label="复核状态" width="120" />
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="review(row.id, 'PASS')">通过</el-button>
                <el-button link type="danger" size="small" @click="review(row.id, 'REJECT')">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="dialog" title="新增质检规则" width="480px">
      <el-form label-width="90px">
        <el-form-item label="规则内容"><el-input v-model="form.pattern" placeholder="命中即扣分" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.ruleType" style="width: 100%">
            <el-option label="关键词" value="KEYWORD" />
            <el-option label="正则" value="REGEX" />
          </el-select>
        </el-form-item>
        <el-form-item label="扣分"><el-input-number v-model="form.scoreDelta" :min="-100" :max="0" /></el-form-item>
        <el-form-item label="级别">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option label="低" value="LOW" />
            <el-option label="警告" value="WARN" />
            <el-option label="高" value="HIGH" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="submit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-actions { margin-bottom: 10px; display: flex; align-items: center; gap: 12px; }
.cs-tip { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
