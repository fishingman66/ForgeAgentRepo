<template>
  <div class="home">
    <section class="hero-panel">
      <div class="hero-copy">
        <h1>智造 ForgeAgent 智能体平台</h1>
        <p>
          面向团队的一站式智能体开发与管理平台:工作空间协作、表单配置与拖拽工作流两种编排方式、
          模型/工具/知识库资源管理、会话运行时与 RAG 检索增强、智能体市场分发,以及平台级安全与用量统计。
        </p>
        <div class="hero-actions">
          <el-button type="primary" @click="goRoute('/agent/agent')">进入 Agent 管理</el-button>
          <el-button plain @click="goRoute('/agent/chat')">开始对话</el-button>
        </div>
      </div>
    </section>

    <div class="content-grid">
      <section class="section-card">
        <div class="section-head">
          <div>
            <h2>核心功能</h2>
          </div>
        </div>
        <div class="product-list">
          <article v-for="product in products" :key="product.name" class="product-card">
            <div class="product-top">
              <div>
                <h3>{{ product.name }}</h3>
                <p>{{ product.summary }}</p>
              </div>
              <span class="product-version">{{ product.version }}</span>
            </div>
            <div class="product-tags">
              <el-tag v-for="tag in product.tags" :key="tag" effect="plain">{{ tag }}</el-tag>
            </div>
            <div class="product-actions">
              <el-button type="primary" plain @click="goRoute(product.primaryUrl)">
                {{ product.primaryLabel }}
              </el-button>
              <el-button plain @click="goRoute(product.secondaryUrl)">{{ product.secondaryLabel }}</el-button>
            </div>
          </article>
        </div>
      </section>

      <section class="section-card capability-card">
        <div class="section-head">
          <div>
            <h2>能力地图</h2>
          </div>
        </div>
        <div class="capability-groups">
          <article v-for="group in capabilityGroups" :key="group.title" class="capability-group">
            <h3>{{ group.title }}</h3>
            <ul>
              <li v-for="item in group.items" :key="item">{{ item }}</li>
            </ul>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup name="Index" lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();

const products = [
  {
    name: 'Agent 开发',
    version: '表单 + 工作流',
    summary: '支持简单配置(模型、工具、知识库绑定)与拖拽工作流编排(DAG、条件分支、静态校验、试运行)两种开发方式。',
    tags: ['拖拽画布', '静态校验', '试运行', '版本快照'],
    primaryLabel: 'Agent 管理',
    primaryUrl: '/agent/agent',
    secondaryLabel: '聊天对话',
    secondaryUrl: '/agent/chat'
  },
  {
    name: '资源层管理',
    version: '模型 · 工具 · 知识库',
    summary: '统一管理模型供应商与模型配置、工具注册、知识库文档解析分块与 Milvus 向量检索,支撑 Agent 能力扩展。',
    tags: ['模型配置', '工具管理', 'RAG', 'Milvus'],
    primaryLabel: '模型管理',
    primaryUrl: '/agent/model',
    secondaryLabel: '知识库',
    secondaryUrl: '/agent/knowledge'
  },
  {
    name: '智能体市场',
    version: '发布 · 审核 · 安装',
    summary: 'Agent 可提交上架到市场,经审核后供其他空间安装使用,支持版本升级与下架联动。',
    tags: ['市场上架', '审核流', '一键安装'],
    primaryLabel: '智能体市场',
    primaryUrl: '/agent/market',
    secondaryLabel: '我的发布',
    secondaryUrl: '/agent/market-my'
  },
  {
    name: '平台管理与安全',
    version: '供应商 · 密钥 · 审计',
    summary: '平台级模型供应商接入、API Key 签发与吊销、操作审计日志,保障多空间协作下的安全与合规。',
    tags: ['供应商配置', 'API 密钥', '审计日志'],
    primaryLabel: '供应商配置',
    primaryUrl: '/agent/provider',
    secondaryLabel: '审计日志',
    secondaryUrl: '/agent/audit'
  },
  {
    name: 'Token 用量统计',
    version: '归因 · 报表',
    summary: '按会话、API 调用、调试台等来源逐次采集 Token 用量,支持空间/Agent 维度聚合报表。',
    tags: ['用量采集', '归因分析', '统计报表'],
    primaryLabel: '用量统计',
    primaryUrl: '/agent/usage',
    secondaryLabel: 'API 密钥',
    secondaryUrl: '/agent/apikey'
  },
  {
    name: '团队工作空间',
    version: '多空间 · 角色',
    summary: '以工作空间隔离 Agent、资源与数据,支持空间管理员/开发者/运行者角色协作。',
    tags: ['空间隔离', '成员管理', '角色权限'],
    primaryLabel: '工作空间',
    primaryUrl: '/agent/workspace',
    secondaryLabel: '聊天对话',
    secondaryUrl: '/agent/chat'
  }
];

const capabilityGroups = [
  {
    title: 'Agent 编排',
    items: ['表单配置(单 Agent / ReAct)', '拖拽工作流画布(DAG)', 'LLM/工具/知识库/条件分支节点', '静态校验与试运行']
  },
  {
    title: '运行时与调试',
    items: ['会话聊天(OpenAI 兼容)', 'RAG 检索增强', 'Token 用量采集', '调试台逐节点追踪']
  },
  {
    title: '平台基建',
    items: ['Spring Boot 4 + Sa-Token', 'MySQL / Redis / Milvus', 'WarmFlow 审核流', '市场分发与安装']
  }
];

const goTarget = (url: string) => {
  window.open(url, '__blank');
};

const goRoute = (path: string) => {
  router.push(path);
};
</script>

<style lang="scss" scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero-panel,
.section-card {
  border-radius: 28px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-surface-bg);
  box-shadow: var(--app-shadow-sm);
  backdrop-filter: blur(18px);
}

.hero-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(280px, 0.7fr);
  gap: 18px;
  padding: 30px;
  background: radial-gradient(circle at top left, rgba(53, 109, 255, 0.16), transparent 30%), var(--app-surface-bg);
}

.hero-copy {
  display: flex;
  flex-direction: column;
  gap: 14px;

  h1 {
    margin: 0;
    font-size: clamp(30px, 4vw, 46px);
    line-height: 1.06;
    letter-spacing: -0.04em;
    color: var(--app-text-title);
  }

  p {
    margin: 0;
    max-width: 760px;
    color: var(--app-text-muted);
    font-size: 15px;
    line-height: 1.8;
  }
}

.hero-badge {
  display: inline-flex;
  width: fit-content;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 8px;
}

.hero-stats {
  display: grid;
  gap: 12px;
}

.stat-card {
  padding: 18px 20px;
  border-radius: 22px;
  background: var(--app-elevated-soft-bg);
  border: 1px solid var(--app-surface-border);
  display: flex;
  flex-direction: column;
  gap: 6px;

  strong {
    color: var(--app-text-title);
    font-size: 24px;
    letter-spacing: -0.03em;
  }

  span {
    color: var(--app-text-muted);
    font-size: 13px;
  }
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(300px, 0.85fr);
  gap: 20px;
}

.section-card {
  padding: 24px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;

  h2 {
    margin: 6px 0 0;
    color: var(--app-text-title);
    font-size: 26px;
    letter-spacing: -0.03em;
  }
}

.section-kicker {
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.product-list,
.capability-groups {
  display: grid;
  gap: 16px;
}

.product-card {
  padding: 22px;
  border-radius: 24px;
  background: var(--app-elevated-soft-bg);
  border: 1px solid var(--app-surface-border);
  transition:
    transform 0.25s ease,
    box-shadow 0.25s ease,
    border-color 0.25s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--app-shadow-sm);
    border-color: rgba(53, 109, 255, 0.2);
  }
}

.product-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;

  h3 {
    margin: 0 0 8px;
    color: var(--app-text-title);
    font-size: 22px;
    letter-spacing: -0.03em;
  }

  p {
    margin: 0;
    color: var(--app-text-muted);
    line-height: 1.8;
  }
}

.product-version {
  flex-shrink: 0;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 12px;
  font-weight: 700;
}

.product-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.product-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}

.capability-card {
  background: radial-gradient(circle at top right, rgba(14, 165, 233, 0.12), transparent 28%), var(--app-surface-bg);
}

.capability-group {
  padding: 18px 18px 18px 20px;
  border-radius: 22px;
  background: var(--app-elevated-soft-bg);
  border: 1px solid var(--app-surface-border);

  h3 {
    margin: 0 0 12px;
    color: var(--app-text-title);
    font-size: 18px;
  }

  ul {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: 10px;
  }

  li {
    position: relative;
    padding-left: 16px;
    color: var(--app-text-muted);
    line-height: 1.7;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 10px;
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--app-accent-strong);
      box-shadow: 0 0 0 5px rgba(53, 109, 255, 0.12);
    }
  }
}

@media (max-width: 960px) {
  .hero-panel,
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .hero-panel,
  .section-card {
    padding: 20px;
    border-radius: 22px;
  }

  .product-top {
    flex-direction: column;
  }
}

html.dark {
  .hero-panel {
    background: radial-gradient(circle at top left, rgba(53, 109, 255, 0.18), transparent 30%), var(--app-surface-bg);
  }
}
</style>
