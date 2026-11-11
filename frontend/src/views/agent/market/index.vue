<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="名称/描述" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="queryParam.category" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="queryParam.sort" style="width: 130px" @change="handleQuery">
            <el-option label="最新发布" value="new" />
            <el-option label="安装量" value="install" />
            <el-option label="评分" value="rating" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" style="margin-top: 10px">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="市场浏览" name="browse" />
        <el-tab-pane label="我的安装" name="installs" />
      </el-tabs>

      <!-- 市场浏览:卡片网格 -->
      <div v-show="activeTab === 'browse'" v-loading="loading">
        <el-empty v-if="!loading && listingList.length === 0" description="没有找到匹配的智能体">
          <el-button type="primary" text @click="resetQuery">清除筛选条件</el-button>
        </el-empty>
        <el-row v-else :gutter="16">
          <el-col v-for="item in listingList" :key="item.id" :xs="24" :sm="12" :md="8" :lg="6" style="margin-bottom: 16px">
            <el-card shadow="hover" class="listing-card" @click="openDetail(item)">
              <div class="card-head">
                <span class="listing-icon">{{ item.icon || '🤖' }}</span>
                <div class="card-title">
                  <span class="name" :title="item.name">{{ item.name }}</span>
                  <el-tag size="small" effect="plain">{{ categoryLabel(item.category) }}</el-tag>
                </div>
              </div>
              <div class="card-desc" :title="item.description">{{ item.description || '暂无描述' }}</div>
              <div class="card-meta">
                <span>{{ item.publisherName || '匿名' }}</span>
                <span>安装 {{ item.installCount }}</span>
                <span>
                  <el-icon color="#f7ba2a"><Star /></el-icon>
                  {{ item.ratingAvg != null ? item.ratingAvg.toFixed(1) : '暂无' }}({{ item.ratingCount }})
                </span>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
      </div>

      <!-- 我的安装 -->
      <div v-show="activeTab === 'installs'" v-loading="installLoading">
        <el-table border :data="installList" class="data-table">
          <el-table-column label="条目" min-width="160" show-overflow-tooltip>
            <template #default="scope">
              <span>{{ scope.row.listingIcon || '🤖' }} {{ scope.row.listingName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="安装到空间" prop="workspaceName" min-width="130" show-overflow-tooltip />
          <el-table-column label="Agent 副本" prop="agentCopyName" min-width="150" show-overflow-tooltip />
          <el-table-column label="版本" width="120" align="center">
            <template #default="scope">
              v{{ scope.row.versionNo }}
              <el-tag v-if="scope.row.latestVersionNo && scope.row.latestVersionNo > scope.row.versionNo" type="warning" size="small" style="margin-left: 4px">
                可升级 v{{ scope.row.latestVersionNo }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="安装时间" prop="createTime" width="170" align="center" />
          <el-table-column label="操作" width="180" align="center" fixed="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.latestVersionNo && scope.row.latestVersionNo > scope.row.versionNo"
                v-hasPermi="['agent:market:install']"
                size="small" text type="warning" icon="Top"
                @click="handleUpgrade((scope.row as MarketInstallVO))"
              >升级</el-button>
              <el-button v-hasPermi="['agent:market:rate']" size="small" text type="primary" icon="Star" @click="openRate((scope.row as MarketInstallVO))">
                {{ scope.row.rated ? '改评' : '评分' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 条目详情抽屉 -->
    <el-drawer v-model="detail.visible" title="智能体详情" size="480px">
      <div v-if="detail.data" class="detail-body">
        <div class="detail-head">
          <span class="listing-icon big">{{ detail.data.icon || '🤖' }}</span>
          <div>
            <h3 style="margin: 0">{{ detail.data.name }}</h3>
            <div style="margin-top: 6px">
              <el-tag size="small" effect="plain">{{ categoryLabel(detail.data.category) }}</el-tag>
              <el-tag size="small" type="info" effect="plain" style="margin-left: 6px">v{{ detail.data.versionNo }}</el-tag>
            </div>
          </div>
        </div>
        <el-descriptions :column="1" border style="margin-top: 16px">
          <el-descriptions-item label="发布者">{{ detail.data.publisherName || '匿名' }}</el-descriptions-item>
          <el-descriptions-item label="安装量">{{ detail.data.installCount }}</el-descriptions-item>
          <el-descriptions-item label="评分">
            <el-rate :model-value="detail.data.ratingAvg || 0" disabled show-score :max="5" />
            <span style="margin-left: 8px; color: #909399">({{ detail.data.ratingCount }} 人评价)</span>
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.data.updateTime }}</el-descriptions-item>
          <el-descriptions-item label="详情说明">{{ detail.data.description || '暂无' }}</el-descriptions-item>
          <el-descriptions-item label="使用说明">{{ detail.data.usageDoc || '暂无' }}</el-descriptions-item>
          <el-descriptions-item label="依赖声明">
            <pre class="deps-pre">{{ depsDisplay }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <el-button v-hasPermi="['agent:market:install']" type="primary" style="width: 100%; margin-top: 16px" icon="Download" @click="openInstall">
          安装到工作空间
        </el-button>
      </div>
    </el-drawer>

    <!-- 安装向导 -->
    <el-dialog v-model="install.visible" title="安装智能体" width="560px" destroy-on-close :close-on-click-modal="false">
      <el-steps :active="install.step" align-center finish-status="success" style="margin-bottom: 20px">
        <el-step title="选择空间" />
        <el-step title="依赖检查" />
        <el-step title="完成" />
      </el-steps>

      <div v-show="install.step === 0">
        <el-form label-width="100px">
          <el-form-item label="目标空间" required>
            <el-select v-model="install.workspaceId" placeholder="选择安装目标工作空间" style="width: 100%">
              <el-option v-for="ws in workspaceOptions" :key="ws.id" :label="ws.name" :value="String(ws.id)" />
            </el-select>
          </el-form-item>
          <el-form-item label="安装条目">
            <span>{{ install.listingName }}</span>
          </el-form-item>
        </el-form>
      </div>

      <div v-show="install.step === 1" v-loading="install.checking">
        <template v-if="install.check">
          <el-alert
            :type="install.check.modelOk ? 'success' : 'error'"
            :closable="false"
            :title="`模型依赖:${install.check.modelName || '未知'} — ${install.check.modelMessage || ''}`"
            style="margin-bottom: 12px"
          />
          <div v-if="install.check.tools?.length" class="dep-group">
            <div class="dep-title">工具依赖</div>
            <div v-for="d in install.check.tools" :key="'t' + d.id" class="dep-item">
              <el-icon v-if="d.found && d.enabled" color="#67c23a"><CircleCheck /></el-icon>
              <el-icon v-else color="#e6a23c"><Warning /></el-icon>
              <span>{{ d.name }}</span>
              <span class="dep-msg">{{ depMsg(d) }}</span>
            </div>
          </div>
          <div v-if="install.check.knowledges?.length" class="dep-group">
            <div class="dep-title">知识库依赖</div>
            <div v-for="d in install.check.knowledges" :key="'k' + d.id" class="dep-item">
              <el-icon v-if="d.found && d.enabled" color="#67c23a"><CircleCheck /></el-icon>
              <el-icon v-else color="#e6a23c"><Warning /></el-icon>
              <span>{{ d.name }}</span>
              <span class="dep-msg">{{ depMsg(d) }}</span>
            </div>
          </div>
          <el-alert
            v-if="!install.check.installable"
            type="error" :closable="false" title="模型依赖不满足,无法安装" style="margin-top: 12px"
          />
          <el-alert v-else type="info" :closable="false" title="缺失的工具/知识库依赖将在副本中跳过" style="margin-top: 12px" />
        </template>
      </div>

      <div v-show="install.step === 2" style="text-align: center; padding: 20px 0">
        <el-icon color="#67c23a" :size="48"><CircleCheck /></el-icon>
        <p>安装成功,已生成 Agent 副本「{{ install.copyName }}」</p>
      </div>

      <template #footer>
        <template v-if="install.step === 0">
          <el-button @click="install.visible = false">取消</el-button>
          <el-button type="primary" :disabled="!install.workspaceId" :loading="install.checking" @click="doInstallCheck">下一步</el-button>
        </template>
        <template v-else-if="install.step === 1">
          <el-button @click="install.step = 0">上一步</el-button>
          <el-button type="primary" :disabled="!install.check?.installable" :loading="install.confirming" @click="doInstallConfirm">确认安装</el-button>
        </template>
        <template v-else>
          <el-button type="primary" @click="install.visible = false">完成</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 评分对话框 -->
    <el-dialog v-model="rate.visible" title="评价智能体" width="440px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item label="条目">
          <span>{{ rate.listingName }}</span>
        </el-form-item>
        <el-form-item label="评分" required>
          <el-rate v-model="rate.stars" :max="5" />
        </el-form-item>
        <el-form-item label="评价">
          <el-input v-model="rate.comment" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rate.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!rate.stars" :loading="rate.saving" @click="submitRate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentMarket">
import {
  browseMarket,
  installCheck,
  installConfirm,
  myInstalls,
  rateInstall,
  upgradeInstall
} from '@/api/agent/market';
import type { InstallCheckVO, InstallDepItem, MarketInstallVO, MarketListingVO, MarketQuery } from '@/api/agent/market/types';
import { myWorkspaces } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const listingList = ref<MarketListingVO[]>([]);
const total = ref(0);
const activeTab = ref('browse');

const queryParam = ref<MarketQuery>({ pageNum: 1, pageSize: 12, keyword: '', category: '', sort: 'new' });

const categoryOptions = [
  { label: '通用', value: 'general' },
  { label: '办公', value: 'office' },
  { label: '开发', value: 'dev' },
  { label: '数据', value: 'data' },
  { label: '生活', value: 'life' },
  { label: '其他', value: 'other' }
];
const categoryLabel = (val: string) => categoryOptions.find((c) => c.value === val)?.label || val;

const getList = async () => {
  loading.value = true;
  try {
    const res = await browseMarket(queryParam.value);
    listingList.value = res.data?.rows || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParam.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParam.value = { pageNum: 1, pageSize: 12, keyword: '', category: '', sort: 'new' };
  handleQuery();
};

// ============ 详情 ============
const detail = reactive<{ visible: boolean; data: MarketListingVO | null }>({ visible: false, data: null });
const depsDisplay = computed(() => {
  if (!detail.data?.depsJson) return '无';
  try {
    return JSON.stringify(JSON.parse(detail.data.depsJson), null, 2);
  } catch {
    return detail.data.depsJson;
  }
});

const openDetail = (item: MarketListingVO) => {
  detail.data = item;
  detail.visible = true;
};

// ============ 安装向导 ============
const workspaceOptions = ref<WorkspaceVO[]>([]);
const install = reactive({
  visible: false,
  step: 0,
  listingId: '',
  listingName: '',
  workspaceId: '',
  checking: false,
  confirming: false,
  check: null as InstallCheckVO | null,
  copyName: ''
});

const openInstall = async () => {
  if (!detail.data) return;
  install.listingId = String(detail.data.id);
  install.listingName = detail.data.name;
  install.step = 0;
  install.check = null;
  install.workspaceId = '';
  if (workspaceOptions.value.length === 0) {
    const res = await myWorkspaces();
    workspaceOptions.value = res.data || [];
  }
  if (workspaceOptions.value.length > 0) {
    install.workspaceId = String(workspaceOptions.value[0].id);
  }
  install.visible = true;
};

const doInstallCheck = async () => {
  install.checking = true;
  try {
    const res = await installCheck({ listingId: install.listingId, workspaceId: install.workspaceId });
    install.check = res.data || null;
    install.step = 1;
  } finally {
    install.checking = false;
  }
};

const depMsg = (d: InstallDepItem) => {
  if (!d.found) return '资源不存在,将跳过';
  if (!d.enabled) return '已停用,将跳过';
  return '正常';
};

const doInstallConfirm = async () => {
  install.confirming = true;
  try {
    await installConfirm({ listingId: install.listingId, workspaceId: install.workspaceId });
    install.step = 2;
    getList();
    loadInstalls();
  } finally {
    install.confirming = false;
  }
};

// ============ 我的安装 ============
const installLoading = ref(false);
const installList = ref<MarketInstallVO[]>([]);

const loadInstalls = async () => {
  installLoading.value = true;
  try {
    const res = await myInstalls();
    installList.value = res.data || [];
  } finally {
    installLoading.value = false;
  }
};

watch(activeTab, (val) => {
  if (val === 'installs') loadInstalls();
});

const handleUpgrade = async (row: MarketInstallVO) => {
  try {
    await proxy?.$modal.confirm(`确认将「${row.listingName}」从 v${row.versionNo} 升级到 v${row.latestVersionNo} 吗?将保留资源绑定,仅更新配置字段。`);
    await upgradeInstall(row.id);
    proxy?.$modal.msgSuccess('升级成功');
    loadInstalls();
  } catch (e) {
    // 用户取消或失败
  }
};

// ============ 评分 ============
const rate = reactive({ visible: false, saving: false, installId: '', listingName: '', stars: 0, comment: '' });

const openRate = (row: MarketInstallVO) => {
  rate.installId = String(row.id);
  rate.listingName = row.listingName;
  rate.stars = 0;
  rate.comment = '';
  rate.visible = true;
};

const submitRate = async () => {
  rate.saving = true;
  try {
    await rateInstall({ installId: rate.installId, stars: rate.stars, comment: rate.comment });
    proxy?.$modal.msgSuccess('评价成功');
    rate.visible = false;
    loadInstalls();
    getList();
  } finally {
    rate.saving = false;
  }
};

onMounted(() => {
  getList();
  loadInstalls();
});
</script>

<style scoped>
.listing-card {
  cursor: pointer;
  height: 100%;
}
.listing-card:hover {
  border-color: var(--el-color-primary);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.listing-icon {
  font-size: 32px;
  line-height: 1;
  flex-shrink: 0;
}
.listing-icon.big {
  font-size: 48px;
}
.card-title {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.card-title .name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-desc {
  color: var(--el-text-color-regular);
  font-size: 13px;
  height: 40px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  margin-bottom: 10px;
}
.card-meta {
  display: flex;
  justify-content: space-between;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 14px;
}
.deps-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 180px;
  overflow: auto;
}
.dep-group {
  margin-bottom: 12px;
}
.dep-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.dep-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
}
.dep-msg {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: auto;
}
</style>
