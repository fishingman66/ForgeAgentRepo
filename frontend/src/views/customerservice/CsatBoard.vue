<script setup lang="ts">
/**
 * 满意度看板(C2):CSAT 邀请明细 + 汇总指标(平均分 / 回收率)。
 * 数据流:listCsat 全量明细 + csatStats 汇总;语音通话挂机结案后
 * 产生的邀请会自动出现在本页(V2),可与通话记录页交叉核对。
 * 扩展点:按渠道 / 按时间段过滤在后端 csatStats 的 from/to 参数上扩展。
 */
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { csatStats, listCsat } from '@/api/customerservice';
import type { CsCsat } from '@/api/customerservice';

const rows = ref<CsCsat[]>([]);
const stats = ref<Record<string, number>>({});
const loading = ref(false);

const cards = [
  { key: 'invited', label: '邀请数', suffix: '' },
  { key: 'submitted', label: '提交数', suffix: '' },
  { key: 'avgScore', label: '平均分', suffix: ' 分' },
  { key: 'responseRate', label: '回收率', suffix: ' %' }
];

const load = async () => {
  loading.value = true;
  try {
    const [listRes, statsRes] = await Promise.all([listCsat(), csatStats()]);
    rows.value = listRes.data ?? [];
    stats.value = statsRes.data ?? {};
  } catch {
    ElMessage.error('满意度数据加载失败');
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-row :gutter="12">
      <el-col v-for="item in cards" :key="item.key" :span="6">
        <el-card shadow="never">
          <div class="cs-label">{{ item.label }}</div>
          <div class="cs-value">{{ stats[item.key] ?? 0 }}{{ item.suffix }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" class="cs-card">
      <template #header>
        <div class="cs-header"><span>评分明细</span><el-button size="small" @click="load">刷新</el-button></div>
      </template>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="conversationId" label="会话ID" min-width="180" />
        <el-table-column prop="score" label="评分" width="90" />
        <el-table-column prop="comment" label="评价" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tags" label="标签" width="140" />
        <el-table-column prop="invitedAt" label="邀请时间" width="170" />
        <el-table-column prop="submittedAt" label="提交时间" width="170" />
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无评价数据" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-card { margin-top: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-label { font-size: 12px; color: var(--el-text-color-secondary); }
.cs-value { font-size: 24px; font-weight: 500; margin-top: 6px; }
</style>
