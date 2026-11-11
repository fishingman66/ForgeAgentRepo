<template>
  <el-breadcrumb class="app-breadcrumb" separator="/">
    <transition-group name="breadcrumb">
      <el-breadcrumb-item v-for="(item, index) in levelList" :key="item.path">
        <span v-if="item.redirect === 'noRedirect' || index == levelList.length - 1" class="no-redirect">
          {{ item.meta?.title }}
        </span>
        <a v-else @click.prevent="handleLink(item)">{{ item.meta?.title }}</a>
      </el-breadcrumb-item>
    </transition-group>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import { RouteLocationMatched } from 'vue-router';
import { usePermissionStore } from '@/store/modules/permission';

const route = useRoute();
const router = useRouter();
const permissionStore = usePermissionStore();
// 面包屑条目来自两种异构来源:菜单树 RouteRecordRaw 与当前路由 RouteLocationMatched;
// 两条来源只共享 path/name/meta/children,统一按结构宽松的数组处理
const levelList = ref<any[]>([]);

const getBreadcrumb = () => {
  // only show routes with meta.title
  let matched: any[] = [];
  const pathNum = findPathNum(route.path);
  // multi-level menu
  if (pathNum > 2) {
    // 按真实路径段切分(保留连字符目录名如 market-center),沿菜单树逐级匹配
    const segments = route.path.split('/').filter(Boolean);
    getMatched(segments, permissionStore.defaultRoutes, matched);
    // 菜单树未命中(如数据变更后的旧路由)时回退到当前路由匹配链,避免面包屑丢层级
    if (matched.length < 2) {
      matched = route.matched.filter(item => item.meta && item.meta.title);
    }
  } else {
    matched = route.matched.filter(item => item.meta && item.meta.title);
  }
  // 判断是否为首页
  if (!isDashboard(matched[0])) {
    matched = [{ path: '/index', meta: { title: '首页' } }].concat(matched);
  }
  levelList.value = matched.filter(item => item.meta && item.meta.title && item.meta.breadcrumb !== false);
};
const findPathNum = (str, char = '/') => {
  if (typeof str !== 'string' || str.length === 0) return 0;
  return str.split(char).length - 1;
};
const getMatched = (segments: string[], routeList: any[], matched: any[]) => {
  if (!segments.length) {
    return;
  }
  const head = segments[0];
  const data = routeList.find(
    item => item.path == head || item.path == `/${head}` || String(item.name ?? '').toLowerCase() == head
  );
  if (!data) {
    return;
  }
  matched.push(data);
  if (data.children && data.children.length) {
    getMatched(segments.slice(1), data.children, matched);
  }
};
const isDashboard = (route: RouteLocationMatched) => {
  const name = route && (route.name as string);
  if (!name) {
    return false;
  }
  return name.trim() === 'Index';
};
const handleLink = item => {
  const { redirect, path } = item;
  redirect ? router.push(redirect) : router.push(path);
};

watchEffect(() => {
  // if you go to the redirect page, do not update the breadcrumbs
  if (route.path.startsWith('/redirect/')) return;
  getBreadcrumb();
});
onMounted(() => {
  getBreadcrumb();
});
</script>

<style lang="scss" scoped>
.app-breadcrumb.el-breadcrumb {
  display: inline-block;
  font-size: 13px;
  line-height: 1.2;
  margin-left: 4px;
  color: var(--app-text-muted);

  :deep(.el-breadcrumb__inner) {
    color: inherit;
    font-weight: 500;
  }

  .no-redirect {
    color: var(--app-text-title);
    cursor: text;
  }
}
</style>
