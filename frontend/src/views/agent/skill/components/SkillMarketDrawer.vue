<template>
  <el-drawer v-model="visible" title="技能市场" size="640px" :destroy-on-close="false">
    <div class="market-toolbar">
      <el-input v-model="keyword" placeholder="搜索技能名称" clearable style="width: 240px" @keyup.enter="load" />
      <el-button type="primary" icon="Search" @click="load">搜索</el-button>
      <div class="market-agent">
        <span class="market-agent-label">安装到</span>
        <el-select v-model="targetAgentId" placeholder="选择 Agent" filterable style="width: 220px">
          <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
        </el-select>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      class="market-tip"
      title="市场只展示平台共享技能；安装=把技能绑定到你选择的 Agent（幂等，重复安装不报错）"
    />

    <el-table v-loading="loading" :data="rows" size="small" empty-text="暂无共享技能">
      <el-table-column prop="name" label="技能" min-width="150" show-overflow-tooltip />
      <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" text icon="Download" @click="install(row as SkillVO)">安装</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="market-pager">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="load"
      />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { installMarketSkill, listMarketSkill } from '@/api/agent/skill-market';
import type { SkillVO } from '@/api/agent/skill/types';
import { listAgent } from '@/api/agent/agent';
import type { AgentVO } from '@/api/agent/agent/types';

/**
 * P3 / C5:技能市场抽屉。
 *
 * 只做「发现 + 绑定」:技能的发布/审核不做（docs/13 把完整市场列为选做，
 * 而共享技能本来就对全平台可见，真正缺的是发现入口）。
 */
const visible = ref(false);
const loading = ref(false);
const keyword = ref('');
const rows = ref<SkillVO[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const agents = ref<AgentVO[]>([]);
const targetAgentId = ref<string | number | undefined>(undefined);

const load = async () => {
  loading.value = true;
  try {
    const res = await listMarketSkill({
      keyword: keyword.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    });
    rows.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } catch {
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
};

const loadAgents = async () => {
  try {
    const res = await listAgent({ pageNum: 1, pageSize: 200 });
    agents.value = res.data?.rows || [];
    if (!targetAgentId.value && agents.value.length) {
      targetAgentId.value = agents.value[0].id;
    }
  } catch {
    agents.value = [];
  }
};

const install = async (row: SkillVO) => {
  if (!targetAgentId.value) {
    ElMessage.warning('请先选择要安装到的 Agent');
    return;
  }
  await installMarketSkill(row.id, targetAgentId.value);
  ElMessage.success(`已把「${row.name}」安装到所选 Agent`);
};

const open = () => {
  visible.value = true;
  pageNum.value = 1;
  load();
  if (!agents.value.length) {
    loadAgents();
  }
};

defineExpose({ open });
</script>

<style scoped>
.market-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.market-agent {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.market-agent-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.market-tip {
  margin-bottom: 10px;
}
.market-pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
