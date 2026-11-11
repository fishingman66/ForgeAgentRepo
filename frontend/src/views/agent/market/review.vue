<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="关键字">
          <el-input v-model="queryParam.keyword" placeholder="名称/描述" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParam.status" placeholder="待审核" clearable style="width: 130px">
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
            <h3>市场审核</h3>
            <span class="heading-tip">默认展示待审核条目;审核通过后自动上架</span>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border :data="listingList" class="data-table">
        <el-table-column label="条目" min-width="160" show-overflow-tooltip>
          <template #default="scope">
            <el-button text type="primary" @click="openDetail((scope.row as MarketListingVO))">{{ scope.row.icon || '🤖' }} {{ scope.row.name }}</el-button>
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
        <el-table-column label="发布者" prop="publisherName" width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="100" align="center">
          <template #default="scope">
            <el-tag :type="statusTag(scope.row.status)" size="small">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="安装量" prop="installCount" width="90" align="center" />
        <el-table-column label="提交时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="scope">
            <template v-if="scope.row.status === '0'">
              <el-button v-hasPermi="['agent:market:audit']" size="small" text type="success" icon="CircleCheck" @click="handleApprove((scope.row as MarketListingVO))">通过</el-button>
              <el-button v-hasPermi="['agent:market:audit']" size="small" text type="danger" icon="CircleClose" @click="openReject((scope.row as MarketListingVO))">驳回</el-button>
            </template>
            <el-button
              v-if="scope.row.status === '1'"
              v-hasPermi="['agent:market:forceoff']"
              size="small" text type="danger" icon="Remove"
              @click="openForceOff((scope.row as MarketListingVO))"
            >强制下架</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-model:page="queryParam.pageNum" v-model:limit="queryParam.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 条目详情抽屉 -->
    <el-drawer v-model="detail.visible" title="条目详情" size="480px">
      <div v-if="detail.data">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="展示名">{{ detail.data.icon || '🤖' }} {{ detail.data.name }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ categoryLabel(detail.data.category) }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detail.data.versionNo }}</el-descriptions-item>
          <el-descriptions-item label="发布者">{{ detail.data.publisherName || '匿名' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.data.status)" size="small">{{ statusLabel(detail.data.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="详情说明">{{ detail.data.description || '暂无' }}</el-descriptions-item>
          <el-descriptions-item label="使用说明">{{ detail.data.usageDoc || '暂无' }}</el-descriptions-item>
          <el-descriptions-item label="依赖声明">
            <pre class="deps-pre">{{ depsDisplay }}</pre>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.data.reviewReason" label="审核理由">{{ detail.data.reviewReason }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>

    <!-- 驳回理由 -->
    <el-dialog v-model="reason.visible" :title="reason.title" width="440px" destroy-on-close :close-on-click-modal="false">
      <el-form ref="reasonFormRef" :model="reason" :rules="reasonRules">
        <el-form-item prop="text">
          <el-input v-model="reason.text" type="textarea" :rows="4" maxlength="300" show-word-limit placeholder="请填写理由(将展示给发布者)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reason.visible = false">取消</el-button>
        <el-button type="primary" :loading="reason.saving" @click="submitReason">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AgentMarketReview">
import { approveListing, forceOffListing, rejectListing, reviewList } from '@/api/agent/market';
import type { MarketListingVO, MarketQuery } from '@/api/agent/market/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const listingList = ref<MarketListingVO[]>([]);
const total = ref(0);

const queryParam = ref<MarketQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: '0' });

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
    const res = await reviewList(queryParam.value);
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
  queryParam.value = { pageNum: 1, pageSize: 10, keyword: '', status: '0' };
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

const openDetail = (row: MarketListingVO) => {
  detail.data = row;
  detail.visible = true;
};

// ============ 审核操作 ============
const handleApprove = async (row: MarketListingVO) => {
  try {
    await proxy?.$modal.confirm(`确认通过「${row.name}」的审核并上架吗?`);
    await approveListing(row.id);
    proxy?.$modal.msgSuccess('已通过并上架');
    getList();
  } catch (e) {
    // 用户取消或失败
  }
};

const reasonFormRef = ref();
const reason = reactive({ visible: false, saving: false, title: '', mode: 'reject' as 'reject' | 'forceoff', id: '', name: '', text: '' });
const reasonRules = {
  text: [{ required: true, message: '理由不能为空', trigger: 'blur' }]
};

const openReject = (row: MarketListingVO) => {
  reason.mode = 'reject';
  reason.id = String(row.id);
  reason.name = row.name;
  reason.text = '';
  reason.title = `驳回「${row.name}」`;
  reason.visible = true;
};

const openForceOff = (row: MarketListingVO) => {
  reason.mode = 'forceoff';
  reason.id = String(row.id);
  reason.name = row.name;
  reason.text = '';
  reason.title = `强制下架「${row.name}」`;
  reason.visible = true;
};

const submitReason = () => {
  reasonFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    reason.saving = true;
    try {
      if (reason.mode === 'reject') {
        await rejectListing(reason.id, reason.text);
        proxy?.$modal.msgSuccess('已驳回');
      } else {
        await forceOffListing(reason.id, reason.text);
        proxy?.$modal.msgSuccess('已强制下架');
      }
      reason.visible = false;
      getList();
    } finally {
      reason.saving = false;
    }
  });
};

onMounted(() => {
  getList();
});
</script>

<style scoped>
.deps-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 180px;
  overflow: auto;
}
</style>
