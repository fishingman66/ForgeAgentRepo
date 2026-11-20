<script setup lang="ts">
/**
 * 公开帮助中心(B1/F1/F3):免登录,渠道定位空间,只读已发布文档 + 已生效 FAQ。
 * 走 /help/{lang}[/{slug}] 路由;后端 /help-api/** 由 Nginx 正则放行,前端用 fetch 直连。
 * F3:UI 文案走 i18n help.*,语言从 URL 前缀解析,未翻译文章由后端回退默认语言。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';

interface HelpArticle {
  slug: string;
  title: string;
  summary: string;
  locale: string;
  source: string;
  updateTime?: string;
  segments?: Array<{ text: string; hit: boolean }>;
  /** F3:当前语言无内容,后端用默认语言兜底 */
  fallback?: boolean;
}

const { t, locale } = useI18n();
const route = useRoute();
const router = useRouter();

const lang = ref('zh_CN');
const channelCode = ref('web'); // 默认网页渠道;可用 ?channel= 覆盖
const articles = ref<HelpArticle[]>([]);
const current = ref<HelpArticle | null>(null);
const keyword = ref('');
const loading = ref(false);
const searched = ref(false);
const feedbackSent = ref(false);

const apiBase = '/help-api';
/** 当前语言下是否出现了默认语言兜底文章 */
const hasFallback = computed(() => articles.value.some((a) => a.fallback));

const helpHref = (targetLang: string) => `/help/${targetLang}`;

/**
 * F3 SEO:SPA 没有 SSR,head 里的 hreflang 必须由路由变化时动态维护。
 * 只声明语言入口页的互链;文章详情按自身 locale 只给 canonical(一篇文章只有一种语言)。
 */
const SEO_ATTR = 'data-help-seo';
const clearSeoLinks = () => {
  document.head.querySelectorAll(`link[${SEO_ATTR}]`).forEach((n) => n.remove());
};
const addSeoLink = (rel: string, href: string, hreflang?: string) => {
  const link = document.createElement('link');
  link.setAttribute('rel', rel);
  link.setAttribute('href', href);
  link.setAttribute(SEO_ATTR, '1');
  if (hreflang) {
    link.setAttribute('hreflang', hreflang);
  }
  document.head.appendChild(link);
};
const applySeoLinks = () => {
  clearSeoLinks();
  const origin = window.location.origin;
  const query = `?channel=${encodeURIComponent(channelCode.value)}`;
  const path = current.value ? `${helpHref(lang.value)}/${current.value.slug}` : helpHref(lang.value);
  addSeoLink('canonical', origin + path + query);
  if (!current.value) {
    for (const l of ['zh_CN', 'en_US']) {
      addSeoLink('alternate', origin + helpHref(l) + query, l === 'en_US' ? 'en' : 'zh-CN');
    }
    addSeoLink('alternate', origin + helpHref('zh_CN') + query, 'x-default');
  }
};

const parseRoute = () => {
  const p = route.params;
  const rlang = Array.isArray(p.lang) ? p.lang[0] : p.lang;
  lang.value = rlang === 'en_US' || rlang === 'zh_CN' ? rlang : 'zh_CN';
  const rslug = Array.isArray(p.slug) ? p.slug[0] : p.slug;
  current.value = rslug ? { slug: rslug, title: '', summary: '', locale: lang.value, source: '' } : null;
  const q = route.query.channel;
  if (typeof q === 'string' && q) {
    channelCode.value = q;
  }
  // D2:UI 语言必须随 URL 前缀切换,否则 /help/en_US 会出现"英文文章 + 中文界面"
  locale.value = lang.value;
  document.documentElement.lang = lang.value === 'en_US' ? 'en' : 'zh-CN';
  document.title = t('help.title');
  applySeoLinks();
};

const apiFetch = async (path: string) => {
  const res = await fetch(apiBase + path, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error('HTTP ' + res.status);
  }
  const body = await res.json();
  if (body && body.code !== undefined && body.code !== 0 && body.code !== 200) {
    throw new Error(body.msg || '请求失败');
  }
  return body?.data;
};

const loadArticles = async () => {
  loading.value = true;
  try {
    articles.value = (await apiFetch(`/${channelCode.value}/articles?locale=${lang.value}`)) || [];
  } catch {
    articles.value = [];
  } finally {
    loading.value = false;
  }
};

/** F1 站内搜索:零命中引导 AI / 转人工。 */
const doSearch = async () => {
  if (!keyword.value.trim()) {
    searched.value = false;
    await loadArticles();
    return;
  }
  loading.value = true;
  searched.value = true;
  try {
    const q = encodeURIComponent(keyword.value.trim());
    articles.value = (await apiFetch(`/${channelCode.value}/search?q=${q}&locale=${lang.value}`)) || [];
  } catch {
    articles.value = [];
  } finally {
    loading.value = false;
  }
};

const loadDetail = async () => {
  if (!current.value) {
    return;
  }
  loading.value = true;
  try {
    const data = await apiFetch(`/${channelCode.value}/articles/${current.value.slug}?locale=${lang.value}`);
    current.value = data || null;
    if (data?.title) {
      document.title = `${data.title} - ${t('help.title')}`;
    }
    if (data?.fallback) {
      ElMessage.info(t('help.fallbackNotice'));
    }
    if (!data) {
      ElMessage.warning(t('help.notFound'));
    }
  } catch {
    ElMessage.warning(t('help.notFound'));
  } finally {
    loading.value = false;
  }
};

const sendFeedback = async (helpful: boolean) => {
  if (!current.value || feedbackSent.value) {
    return;
  }
  try {
    await fetch(`${apiBase}/${channelCode.value}/articles/${current.value.slug}/feedback?helpful=${helpful}`,
      { method: 'POST', headers: { Accept: 'application/json' } });
    feedbackSent.value = true;
    ElMessage.success(t('help.feedbackDone'));
  } catch {
    ElMessage.warning(t('help.feedbackDone'));
  }
};

const switchLang = (target: string) => {
  router.push({ path: helpHref(target), query: route.query });
};

const backToList = () => {
  router.push({ path: helpHref(lang.value), query: route.query });
};

const formatTime = (v?: string) => {
  if (!v) return '';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString();
};

watch(() => route.fullPath, parseRoute);
watch(lang, () => {
  current.value ? loadDetail() : loadArticles();
});

onMounted(() => {
  parseRoute();
  current.value ? loadDetail() : loadArticles();
});
</script>

<template>
  <div class="help-center">
    <header class="hc-header">
      <div class="hc-inner">
        <div class="hc-brand" @click="backToList">{{ t('help.title') }}</div>
        <nav class="hc-lang">
          <a :class="{ active: lang === 'zh_CN' }" @click="switchLang('zh_CN')">中文</a>
          <span class="hc-lang-sep">/</span>
          <a :class="{ active: lang === 'en_US' }" @click="switchLang('en_US')">EN</a>
        </nav>
      </div>
    </header>

    <main class="hc-main">
      <template v-if="!current">
        <section class="hc-hero">
          <h1>{{ t('help.title') }}</h1>
          <p>{{ t('help.subtitle') }}</p>
          <div class="hc-search">
            <input v-model="keyword" type="text" :placeholder="t('help.searchPlaceholder')"
                   @keyup.enter="doSearch" />
            <button type="button" :disabled="loading" @click="doSearch">
              {{ loading ? t('help.searching') : t('help.searchButton') }}
            </button>
          </div>
        </section>

        <section class="hc-list">
          <h2>{{ searched ? t('help.searchButton') : t('help.allArticles') }} · {{ articles.length }}</h2>
          <div v-if="hasFallback" class="hc-fallback">{{ t('help.fallbackNotice') }}</div>
          <div v-if="loading" class="hc-empty">{{ t('help.searching') }}</div>
          <div v-else-if="!articles.length" class="hc-empty">
            <p>{{ t('help.empty') }}</p>
            <p class="hc-empty-hint">{{ t('help.emptyHint') }}</p>
          </div>
          <div v-for="a in articles" :key="a.slug" class="hc-card" @click="router.push({ path: helpHref(lang) + '/' + a.slug, query: route.query })">
            <div class="hc-card-title">{{ a.title }}</div>
            <div class="hc-card-summary">
              <template v-if="a.segments?.length"><template v-for="(seg, si) in a.segments" :key="si"><mark v-if="seg.hit" class="hc-hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></template>
              <template v-else>{{ a.summary }}</template>
            </div>
            <div class="hc-card-meta">
              <span class="hc-tag">{{ a.source === 'faq' ? t('help.faq') : t('help.latest') }}</span>
              <span v-if="a.locale !== lang" class="hc-tag hc-tag-muted">{{ a.locale }}</span>
              <span v-if="a.fallback" class="hc-tag hc-tag-muted">{{ t('help.notTranslated') }}</span>
              <span class="hc-time">{{ formatTime(a.updateTime) }}</span>
            </div>
          </div>
        </section>
      </template>

      <template v-else>
        <section class="hc-article">
          <button class="hc-back" type="button" @click="backToList">← {{ t('help.back') }}</button>
          <div v-if="current.fallback" class="hc-fallback">{{ t('help.fallbackNotice') }}</div>
          <h1>{{ current.title || t('help.latest') }}</h1>
          <div class="hc-article-body">{{ current.summary }}</div>
          <div class="hc-feedback">
            <span>{{ t('help.wasHelpful') }}</span>
            <button v-if="!feedbackSent" type="button" @click="sendFeedback(true)">👍 {{ t('help.helpful') }}</button>
            <button v-if="!feedbackSent" type="button" @click="sendFeedback(false)">👎 {{ t('help.notHelpful') }}</button>
            <span v-else class="hc-feedback-done">{{ t('help.feedbackDone') }}</span>
          </div>
        </section>
      </template>
    </main>

    <footer class="hc-footer">{{ t('help.poweredBy') }}</footer>
  </div>
</template>

<style scoped>
.help-center { min-height: 100vh; display: flex; flex-direction: column; background: var(--el-bg-color-page); color: var(--el-text-color-primary); }
.hc-header { border-bottom: 1px solid var(--el-border-color-lighter); background: var(--el-bg-color); }
.hc-inner { max-width: 860px; margin: 0 auto; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; }
.hc-brand { font-size: 18px; font-weight: 600; cursor: pointer; }
.hc-lang a { cursor: pointer; color: var(--el-text-color-regular); font-size: 14px; }
.hc-lang a.active { color: var(--el-color-primary); font-weight: 600; }
.hc-lang-sep { margin: 0 6px; color: var(--el-text-color-placeholder); }
.hc-main { flex: 1; max-width: 860px; width: 100%; margin: 0 auto; padding: 28px 20px; box-sizing: border-box; }
.hc-hero { text-align: center; padding: 24px 0 32px; }
.hc-hero h1 { font-size: 30px; margin: 0 0 8px; }
.hc-hero p { color: var(--el-text-color-secondary); margin: 0 0 22px; }
.hc-search { display: flex; max-width: 560px; margin: 0 auto; gap: 8px; }
.hc-search input { flex: 1; padding: 10px 14px; border: 1px solid var(--el-border-color); border-radius: 6px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 15px; }
.hc-search button { padding: 10px 20px; border: none; border-radius: 6px; background: var(--el-color-primary); color: #fff; cursor: pointer; font-size: 15px; }
.hc-search button:disabled { opacity: 0.6; cursor: not-allowed; }
.hc-list h2 { font-size: 16px; margin: 8px 0 16px; }
.hc-card { padding: 14px 16px; margin-bottom: 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-bg-color); cursor: pointer; transition: border-color 0.2s; }
.hc-card:hover { border-color: var(--el-color-primary); }
.hc-card-title { font-size: 16px; font-weight: 600; margin-bottom: 6px; }
.hc-hit { background: var(--el-color-warning-light-7); color: var(--el-color-warning-dark-2); padding: 0 2px; border-radius: 2px; }
.hc-card-summary { font-size: 13px; color: var(--el-text-color-regular); line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.hc-card-meta { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.hc-tag { font-size: 11px; padding: 2px 7px; border-radius: 4px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.hc-tag-muted { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.hc-time { font-size: 12px; color: var(--el-text-color-placeholder); margin-left: auto; }
.hc-empty { padding: 32px; text-align: center; color: var(--el-text-color-secondary); }
.hc-fallback { margin: 0 0 12px; padding: 8px 12px; border-radius: 6px; font-size: 13px; background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); }
.hc-empty-hint { font-size: 13px; color: var(--el-text-color-placeholder); }
.hc-back { border: none; background: none; color: var(--el-color-primary); cursor: pointer; padding: 0 0 12px; font-size: 14px; }
.hc-article h1 { font-size: 26px; margin: 8px 0 20px; }
.hc-article-body { line-height: 1.9; font-size: 15px; white-space: pre-wrap; }
.hc-feedback { margin-top: 36px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 14px; }
.hc-feedback button { padding: 6px 14px; border: 1px solid var(--el-border-color); border-radius: 5px; background: var(--el-bg-color); color: var(--el-text-color-regular); cursor: pointer; }
.hc-feedback-done { color: var(--el-color-success); }
.hc-footer { text-align: center; padding: 20px; font-size: 12px; color: var(--el-text-color-placeholder); border-top: 1px solid var(--el-border-color-lighter); }
</style>
