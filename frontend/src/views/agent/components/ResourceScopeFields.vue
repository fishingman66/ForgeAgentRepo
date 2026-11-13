<script setup lang="ts">
/** 资源域双模式:三张资源表共用的「共享范围 / 归属空间」表单字段 */
import { computed, onMounted, ref, watch } from 'vue';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';
import { useUserStore } from '@/store/modules/user';

const model = defineModel<Record<string, any>>({ required: true });
const workspaces = ref<WorkspaceVO[]>([]);
const userStore = useUserStore();
// 超管权限由后端 SysPermissionService 下发 '*:*:*'(与前端 checkPermi 同口径)。
// 不用 userId 硬编码:超管 ID 超出 JS 安全整数范围,直接比较会有精度问题。
// 普通用户即使伪造 shared=true,后端 ResourceAccessGuard 仍会拒绝
const canShare = computed(() => userStore.permissions.includes('*:*:*'));

onMounted(async () => {
  try {
    const res = await myWorkspaces();
    workspaces.value = (res.data ?? []) as WorkspaceVO[];
  } catch {
    workspaces.value = [];
  }
  // 编辑共享资源(id 存在、无归属空间)时回显为平台共享;新建默认空间私有
  if (model.value.id && !model.value.workspaceId && !model.value.shared) {
    model.value.shared = true;
  }
  if (!model.value.shared && !model.value.workspaceId && workspaces.value.length) {
    model.value.workspaceId = workspaces.value[0].id;
  }
});

watch(
  () => model.value.shared,
  (shared) => {
    if (shared) {
      model.value.workspaceId = undefined;
    } else if (!model.value.workspaceId && workspaces.value.length) {
      model.value.workspaceId = workspaces.value[0].id;
    }
  }
);
</script>

<template>
  <div class="resource-scope-fields">
    <el-form-item label="共享范围">
      <el-radio-group v-model="model.shared">
        <el-radio :value="false">空间私有</el-radio>
        <el-radio v-if="canShare" :value="true">平台共享（仅超管）</el-radio>
      </el-radio-group>
      <span v-if="model.shared" class="scope-hint">全空间可见，仅超管可修改</span>
    </el-form-item>
    <el-form-item v-if="!model.shared" label="归属空间" prop="workspaceId">
      <el-select v-model="model.workspaceId" placeholder="请选择归属工作空间" filterable style="width: 100%">
        <el-option v-for="w in workspaces" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
    </el-form-item>
  </div>
</template>

<style scoped>
.scope-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.resource-scope-fields {
  display: contents;
}
</style>
