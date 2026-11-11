<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="名称/描述" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParam.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="待审核" value="0" />
            <el-option label="已上架" value="1" />
            <el-option label="已驳回" value="2" />
            <el-option label="强制下架" value="3" />
            <el-option label="已下架" value="4" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel" style="margin-top: 10px">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>我的发布</h3>
            <span class="heading-tip">将本人已发布的 Agent 提交到市场,审核通过后上架</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:market:publish']" type="primary" plain icon="Plus" @click="openPublish">提交发布</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="listingList" class="data-table">
        <el-table-column label="展示名" min-width="150" show-overflow-tooltip>
          <template #default="scope">
            <span>{{ scope.row.icon || '🤖' }} {{ scope.row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="90" align="center">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ categoryLabel(scope.row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="80" align="center">
          <template #default="scope">v{{ scope.row.versionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="scope">
            <el-tag :type="statusTag(scope.row.status)" size="small">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="驳回/下架理由" prop="reviewReason" min-width="160" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.reviewReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="安装量" prop="installCount" width="90" align="center" />
        <el-table-column label="评分" width="90" align="center">
          <template #default="scope">
            {{ scope.row.ratingAvg != null ? scope.row.ratingAvg.toFixed(1) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" prop="updateTime" width="170" align="center" />
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === '1'"
              v-hasPermi="['agent:market:offline']"
              size="small" text type="danger" icon="Bottom"
              @click="handleOffline((scope.row as MarketListingVO))"
            >下架</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 提交发布 -->
    <el-dialog v-model="publish.visible" title="提交发布到市场" width="560px" destroy-on-close :close-on-click-modal="false">
      <el-form ref="publishFormRef" :model="publish.form" :rules="publishRules" label-width="100px">
        <el-form-item label="选择 Agent" prop="agentId">
          <el-select v-model="publish.form.agentId" placeholder="仅可发布本人已发布的 Agent" style="width: 100%" @change="onAgentChange">
            <el-option v-for="ag in agentOptions" :key="ag.id" :label="`${ag.name}(${ag.workspaceName || ''})`" :value="String(ag.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="市场展示名" prop="name">
          <el-input v-model="publish.form.name" maxlength="50" show-word-limit placeholder="市场中的展示名称" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="publish.form.category" style="width: 100%">
            <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="publish.form.icon" maxlength="200" placeholder="emoji 或图片 URL,默认 🤖" />
        </el-form-item>
        <el-form-item label="详情说明">
          <el-input v-model="publish.form.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="使用说明">
          <el-input v-model="publish.form.usageDoc" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publish.visible = false">取消</el-button>
        <el-button type="primary" :loading="publish.saving" @click="submitPublish">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentMarketMy">
import { myPublishList, offlineListing, publishListing } from '@/api/agent/market';
import type { MarketListingVO, MarketPublishForm, MarketQuery } from '@/api/agent/market/types';
import { listPublishedAgent } from '@/api/agent/agent';
import type { AgentVO } from '@/api/agent/agent/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const listingList = ref<MarketListingVO[]>([]);
const total = ref(0);

const queryParam = ref<MarketQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '' });

const categoryOptions = [
  { label: '通用', value: 'general' },
  { label: '办公', value: 'office' },
  { label: '开发', value: 'dev' },
  { label: '数据', value: 'data' },
  { label: '生活', value: 'life' },
  { label: '其他', value: 'other' }
];
const categoryLabel = (val: string) => categoryOptions.find((c) => c.value === val)?.label || val;

const statusLabel = (status: string) => ({ '0': '待审核', '1': '已上架', '2': '已驳回', '3': '强制下架', '4': '已下架' }[status] || status);
const statusTag = (status: string): 'info' | 'success' | 'danger' | 'warning' | 'primary' =>
  ({ '0': 'warning', '1': 'success', '2': 'danger', '3': 'danger', '4': 'info' }[status] as never) || 'info';

const getList = async () => {
  loading.value = true;
  try {
    const res = await myPublishList(queryParam.value);
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
  queryParam.value = { pageNum: 1, pageSize: 10, keyword: '', status: '' };
  handleQuery();
};

// ============ 提交发布 ============
const agentOptions = ref<AgentVO[]>([]);
const publishFormRef = ref();
const publish = reactive<{ visible: boolean; saving: boolean; form: MarketPublishForm }>({
  visible: false,
  saving: false,
  form: { agentId: undefined, name: '', category: 'general', icon: '', description: '', usageDoc: '' }
});

const publishRules = {
  agentId: [{ required: true, message: '请选择要发布的 Agent', trigger: 'change' }],
  name: [{ required: true, message: '请输入市场展示名', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }]
};

const openPublish = async () => {
  publish.form = { agentId: undefined, name: '', category: 'general', icon: '', description: '', usageDoc: '' };
  if (agentOptions.value.length === 0) {
    const res = await listPublishedAgent();
    agentOptions.value = res.data || [];
  }
  publish.visible = true;
};

const onAgentChange = (val: string) => {
  const ag = agentOptions.value.find((a) => String(a.id) === val);
  if (ag && !publish.form.name) {
    publish.form.name = ag.name;
  }
};

const submitPublish = () => {
  publishFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    publish.saving = true;
    try {
      await publishListing(publish.form);
      proxy?.$modal.msgSuccess('已提交审核');
      publish.visible = false;
      getList();
    } finally {
      publish.saving = false;
    }
  });
};

const handleOffline = async (row: MarketListingVO) => {
  try {
    await proxy?.$modal.confirm(`确认将「${row.name}」从市场下架吗?已安装的副本不受影响。`);
    await offlineListing(row.id);
    proxy?.$modal.msgSuccess('已下架');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

onMounted(() => {
  getList();
});
</script>
