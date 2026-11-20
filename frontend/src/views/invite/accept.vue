<script setup lang="ts">
/**
 * F2 邀请接受落地页:非白名单路由,未登录访问会被 permission.ts 自动带 redirect 送去登录,
 * 登录成功跳回本页后 onMounted 自动调用 acceptInvite(token)——用户无需二次点击。
 * acceptInvite 服务端幂等(已加入成员重复调用成功),刷新重试安全。
 */
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { acceptInvite } from '@/api/agent/workspace';

const route = useRoute();
const router = useRouter();
const status = ref<'loading' | 'done' | 'fail'>('loading');
const message = ref('正在加入空间...');

onMounted(async () => {
  const token = typeof route.query.token === 'string' ? route.query.token : '';
  if (!token) {
    status.value = 'fail';
    message.value = '邀请链接无效:缺少令牌';
    return;
  }
  try {
    await acceptInvite(token);
    status.value = 'done';
    message.value = '已成功加入工作空间,正在跳转...';
    ElMessage.success('已加入工作空间');
    setTimeout(() => router.replace('/index'), 800);
  } catch (e) {
    status.value = 'fail';
    message.value = (e as { msg?: string })?.msg || '加入失败,请确认链接是否有效或已过期';
  }
});
</script>

<template>
  <div class="invite-accept">
    <div class="invite-card">
      <div class="invite-icon" :class="status">{{ status === 'loading' ? '…' : status === 'done' ? '✓' : '!' }}</div>
      <h2>空间邀请</h2>
      <p>{{ message }}</p>
      <el-button v-if="status === 'fail'" type="primary" @click="router.replace('/index')">返回首页</el-button>
    </div>
  </div>
</template>

<style scoped>
.invite-accept { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--el-bg-color-page); }
.invite-card { padding: 40px 48px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-bg-color); text-align: center; min-width: 320px; }
.invite-icon { width: 48px; height: 48px; line-height: 48px; margin: 0 auto 16px; border-radius: 50%; font-size: 24px; background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.invite-icon.done { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.invite-icon.fail { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.invite-card h2 { font-size: 18px; font-weight: 500; margin: 0 0 10px; }
.invite-card p { font-size: 13px; color: var(--el-text-color-secondary); margin: 0 0 20px; }
</style>
