<template>
  <div class="p-2 app-container agent-workspace-page">
    <el-card shadow="hover" class="search-panel">
      <el-form :inline="true" class="query-form">
        <el-form-item label="空间名称">
          <el-input v-model="queryKeyword" placeholder="请输入名称关键字" clearable style="width: 220px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item v-if="activeTab === 'all'" label="归属部门">
          <el-tree-select
            v-model="queryDeptId"
            :data="deptOptions"
            :props="{ value: 'id', label: 'label', children: 'children' } as any"
            value-key="id"
            placeholder="请选择部门"
            check-strictly
            clearable
            style="width: 220px"
          />
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
          <div class="table-heading"><h3>工作空间</h3></div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['agent:workspace:add']" type="primary" plain icon="Plus" @click="handleAdd">新建空间</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane label="我的空间" name="my" />
        <el-tab-pane v-hasPermi="['agent:workspace:list']" label="全部空间" name="all" />
      </el-tabs>

      <!-- 我的空间:卡片视图 -->
      <div v-if="activeTab === 'my'" v-loading="loading" class="ws-cards">
        <el-empty v-if="myList.length === 0" description="暂无加入的工作空间" />
        <el-row :gutter="12">
          <el-col v-for="ws in myList" :key="ws.id" :xs="24" :sm="12" :md="8" :lg="6">
            <el-card shadow="hover" class="ws-card" :class="{ 'is-disabled': ws.status === '1' }">
              <template #header>
                <div class="ws-card-head">
                  <span class="ws-name">{{ ws.name }}</span>
                  <el-tag size="small" :type="ws.status === '0' ? 'success' : 'info'">
                    {{ ws.status === '0' ? '正常' : '已停用' }}
                  </el-tag>
                </div>
              </template>
              <div class="ws-desc">{{ ws.description || '暂无描述' }}</div>
              <div class="ws-meta">
                <el-tag size="small" effect="plain">{{ roleLabel(ws.myRole) }}</el-tag>
                <span>成员 {{ ws.memberCount ?? 0 }}</span>
                <span>所有者 {{ ws.ownerName || ws.ownerId }}</span>
              </div>
              <div class="ws-actions">
                <el-button v-hasPermi="['agent:workspace:member']" size="small" text type="primary" icon="User" @click="openMembers(ws)">成员</el-button>
                <el-button v-hasPermi="['agent:workspace:edit']" size="small" text type="warning" icon="Edit" @click="handleEdit(ws)">编辑</el-button>
                <el-button
                  v-hasPermi="['agent:workspace:edit']"
                  size="small"
                  text
                  :type="ws.status === '0' ? 'info' : 'success'"
                  :icon="ws.status === '0' ? 'CircleClose' : 'CircleCheck'"
                  @click="handleToggleStatus(ws)"
                >
                  {{ ws.status === '0' ? '停用' : '启用' }}
                </el-button>
                <el-button v-hasPermi="['agent:workspace:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(ws)">删除</el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 全部空间:表格视图 -->
      <template v-else>
        <el-table v-loading="loading" border :data="allList" class="data-table">
          <el-table-column label="ID" prop="id" width="180" align="center" />
          <el-table-column label="空间名称" prop="name" min-width="140" show-overflow-tooltip />
          <el-table-column label="描述" prop="description" min-width="160" show-overflow-tooltip />
          <el-table-column label="状态" width="90" align="center">
            <template #default="scope">
              <el-tag size="small" :type="scope.row.status === '0' ? 'success' : 'info'">
                {{ scope.row.status === '0' ? '正常' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成员数" prop="memberCount" width="80" align="center" />
          <el-table-column label="归属部门" width="140" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.deptName || '未分配' }}</template>
          </el-table-column>
          <el-table-column label="所有者" prop="ownerName" width="120" show-overflow-tooltip />
          <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
          <el-table-column label="操作" width="240" align="center" fixed="right">
            <template #default="scope">
              <el-button v-hasPermi="['agent:workspace:member']" size="small" text type="primary" icon="User" @click="openMembers(scope.row as WorkspaceVO)">成员</el-button>
              <el-button v-hasPermi="['agent:workspace:edit']" size="small" text type="warning" icon="Edit" @click="handleEdit(scope.row as WorkspaceVO)">编辑</el-button>
              <el-button
                v-hasPermi="['agent:workspace:edit']"
                size="small"
                text
                :type="scope.row.status === '0' ? 'info' : 'success'"
                @click="handleToggleStatus(scope.row as WorkspaceVO)"
              >
                {{ scope.row.status === '0' ? '停用' : '启用' }}
              </el-button>
              <el-button v-hasPermi="['agent:workspace:remove']" size="small" text type="danger" icon="Delete" @click="handleDelete(scope.row as WorkspaceVO)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-model:page="pageQuery.pageNum" v-model:limit="pageQuery.pageSize" :total="total" @pagination="getAllList" />
      </template>
    </el-card>

    <!-- 新建/编辑空间 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="480px" destroy-on-close>
      <el-form ref="wsFormRef" :model="wsForm" :rules="wsRules" label-width="90px">
        <el-form-item label="空间名称" prop="name">
          <el-input v-model="wsForm.name" placeholder="2-30 字符,全局唯一" maxlength="30" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="wsForm.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="归属部门" prop="deptId">
          <el-tree-select
            v-model="wsForm.deptId"
            :data="deptOptions"
            :props="{ value: 'id', label: 'label', children: 'children' } as any"
            value-key="id"
            placeholder="不选默认归属当前用户部门"
            check-strictly
            clearable
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitWorkspace">确定</el-button>
      </template>
    </el-dialog>

    <!-- 成员管理抽屉 -->
    <el-drawer v-model="memberDrawer.visible" :title="`成员管理 · ${memberDrawer.wsName}`" size="620px" destroy-on-close>
      <div class="member-toolbar">
        <el-select
          v-model="memberDrawer.selectedUser"
          filterable
          remote
          clearable
          placeholder="搜索并选择用户加入"
          :remote-method="searchCandidates"
          :loading="memberDrawer.candidateLoading"
          style="width: 260px"
        >
          <el-option v-for="u in memberDrawer.candidates" :key="u.userId" :value="u.userId" :label="`${u.nickName}(${u.userName})`" />
        </el-select>
        <el-select v-model="memberDrawer.selectedRole" style="width: 130px">
          <el-option label="开发者" value="developer" />
          <el-option label="运行者" value="runner" />
          <el-option label="空间管理员" value="admin" />
        </el-select>
        <el-button type="primary" :disabled="!memberDrawer.selectedUser" :loading="memberDrawer.adding" @click="handleAddMember">添加</el-button>
      </div>

      <el-table v-loading="memberDrawer.loading" border :data="memberDrawer.list" size="small">
        <el-table-column label="成员" min-width="140">
          <template #default="scope">
            {{ scope.row.nickName || scope.row.userName }}
            <span style="color: var(--el-text-color-secondary); font-size: 12px">({{ scope.row.userName }})</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="150" align="center">
          <template #default="scope">
            <el-select
              :model-value="scope.row.role"
              size="small"
              :disabled="!hasMemberPerm"
              @change="(val: string) => handleChangeRole(scope.row as WorkspaceMemberVO, val)"
            >
              <el-option label="空间管理员" value="admin" />
              <el-option label="开发者" value="developer" />
              <el-option label="运行者" value="runner" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="加入时间" prop="createTime" width="160" align="center" />
        <el-table-column label="操作" width="90" align="center">
          <template #default="scope">
            <el-button size="small" text type="danger" :disabled="!hasMemberPerm" @click="handleRemoveMember(scope.row as WorkspaceMemberVO)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination
        v-model:page="memberDrawer.pageNum"
        v-model:limit="memberDrawer.pageSize"
        :total="memberDrawer.total"
        @pagination="getMemberList"
      />
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="AgentWorkspace">
import {
  addMember,
  addWorkspace,
  changeMemberRole,
  changeWorkspaceStatus,
  delMember,
  delWorkspace,
  listCandidates,
  listMember,
  listWorkspace,
  myWorkspaces,
  updateWorkspace
} from '@/api/agent/workspace';
import type { WorkspaceForm, WorkspaceMemberVO, WorkspaceVO } from '@/api/agent/workspace/types';
import { deptTreeSelect } from '@/api/system/user';
import type { DeptTreeVO } from '@/api/system/dept/types';
import { checkPermi } from '@/utils/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

const loading = ref(false);
const activeTab = ref('my');
const queryKeyword = ref('');
const queryDeptId = ref<number | string>();
const deptOptions = ref<DeptTreeVO[]>([]);

const myList = ref<WorkspaceVO[]>([]);
const allList = ref<WorkspaceVO[]>([]);
const total = ref(0);
const pageQuery = reactive({ pageNum: 1, pageSize: 10 });

const hasMemberPerm = computed(() => checkPermi(['agent:workspace:member']));

const roleLabel = (role?: string) => {
  const map: Record<string, string> = { admin: '空间管理员', developer: '开发者', runner: '运行者' };
  return role ? map[role] || role : '未加入';
};

// ---------- 空间列表 ----------
const getMyList = async () => {
  loading.value = true;
  try {
    const res = await myWorkspaces(queryKeyword.value || undefined);
    myList.value = res.data || [];
  } finally {
    loading.value = false;
  }
};

const getAllList = async () => {
  loading.value = true;
  try {
    const res = await listWorkspace({ ...pageQuery, keyword: queryKeyword.value || undefined, deptId: queryDeptId.value || undefined });
    allList.value = res.data.rows || [];
    total.value = res.data.total || 0;
  } finally {
    loading.value = false;
  }
};

const loadDeptOptions = async () => {
  const res = await deptTreeSelect();
  deptOptions.value = res.data || [];
};

const handleQuery = () => {
  pageQuery.pageNum = 1;
  activeTab.value === 'my' ? getMyList() : getAllList();
};

const resetQuery = () => {
  queryKeyword.value = '';
  queryDeptId.value = undefined;
  handleQuery();
};

const handleTabChange = () => handleQuery();

// ---------- 空间表单 ----------
const dialog = reactive({ visible: false, title: '新建空间', saving: false });
const wsFormRef = ref();
const wsForm = reactive<WorkspaceForm>({ id: undefined, name: '', description: '', deptId: undefined });

const wsRules = {
  name: [
    { required: true, message: '空间名称不能为空', trigger: 'blur' },
    { min: 2, max: 30, message: '长度 2-30 字符', trigger: 'blur' }
  ]
};

const handleAdd = () => {
  Object.assign(wsForm, { id: undefined, name: '', description: '', deptId: undefined });
  dialog.title = '新建空间';
  dialog.visible = true;
};

const handleEdit = (ws: WorkspaceVO) => {
  Object.assign(wsForm, { id: ws.id, name: ws.name, description: ws.description, deptId: ws.deptId });
  dialog.title = '编辑空间';
  dialog.visible = true;
};

const submitWorkspace = async () => {
  const valid = await wsFormRef.value.validate().then(() => true).catch(() => false);
  if (!valid) return;
  dialog.saving = true;
  try {
    if (wsForm.id) {
      await updateWorkspace(wsForm);
      ElMessage.success('更新成功');
    } else {
      await addWorkspace(wsForm);
      ElMessage.success('创建成功,您已成为该空间管理员');
    }
    dialog.visible = false;
    handleQuery();
  } catch {
    // 业务错误已由请求拦截器统一提示
  } finally {
    dialog.saving = false;
  }
};

const handleToggleStatus = async (ws: WorkspaceVO) => {
  const target = ws.status === '0' ? '1' : '0';
  try {
    await ElMessageBox.confirm(
      `确认${target === '1' ? '停用' : '启用'}空间「${ws.name}」?${target === '1' ? '停用后空间内资源不可操作。' : ''}`,
      '提示',
      { type: 'warning' }
    );
    await changeWorkspaceStatus(ws.id, target);
    ElMessage.success('操作成功');
    handleQuery();
  } catch {
    // 取消确认或业务错误,拦截器已提示
  }
};

const handleDelete = async (ws: WorkspaceVO) => {
  try {
    await ElMessageBox.confirm(`确认删除空间「${ws.name}」?成员关系将一并清除,该操作不可恢复。`, '警告', { type: 'warning' });
    await delWorkspace(ws.id);
    ElMessage.success('删除成功');
    handleQuery();
  } catch {
    // 取消确认或业务错误,拦截器已提示
  }
};

// ---------- 成员管理 ----------
const memberDrawer = reactive({
  visible: false,
  wsId: '' as string | number,
  wsName: '',
  loading: false,
  adding: false,
  list: [] as WorkspaceMemberVO[],
  total: 0,
  pageNum: 1,
  pageSize: 10,
  candidates: [] as WorkspaceMemberVO[],
  candidateLoading: false,
  selectedUser: undefined as number | string | undefined,
  selectedRole: 'developer'
});

const openMembers = (ws: WorkspaceVO) => {
  memberDrawer.wsId = ws.id;
  memberDrawer.wsName = ws.name;
  memberDrawer.visible = true;
  memberDrawer.selectedUser = undefined;
  memberDrawer.candidates = [];
  getMemberList();
  searchCandidates('');
};

const getMemberList = async () => {
  memberDrawer.loading = true;
  try {
    const res = await listMember(memberDrawer.wsId, { pageNum: memberDrawer.pageNum, pageSize: memberDrawer.pageSize });
    memberDrawer.list = res.data.rows || [];
    memberDrawer.total = res.data.total || 0;
  } finally {
    memberDrawer.loading = false;
  }
};

const searchCandidates = async (keyword: string) => {
  memberDrawer.candidateLoading = true;
  try {
    const res = await listCandidates(memberDrawer.wsId, keyword || undefined);
    memberDrawer.candidates = res.data || [];
  } finally {
    memberDrawer.candidateLoading = false;
  }
};

const handleAddMember = async () => {
  memberDrawer.adding = true;
  try {
    await addMember({ workspaceId: memberDrawer.wsId, userId: memberDrawer.selectedUser, role: memberDrawer.selectedRole });
    ElMessage.success('成员已加入');
    memberDrawer.selectedUser = undefined;
    getMemberList();
    searchCandidates('');
  } catch {
    // 业务错误已由请求拦截器统一提示
  } finally {
    memberDrawer.adding = false;
  }
};

const handleChangeRole = async (row: WorkspaceMemberVO, role: string) => {
  try {
    await changeMemberRole({ id: row.id, role });
    ElMessage.success('角色已变更');
    getMemberList();
  } catch {
    getMemberList();
  }
};

const handleRemoveMember = async (row: WorkspaceMemberVO) => {
  try {
    await ElMessageBox.confirm(`确认将「${row.nickName || row.userName}」移出空间?`, '提示', { type: 'warning' });
    await delMember(row.id);
    ElMessage.success('已移除');
    getMemberList();
    searchCandidates('');
  } catch {
    // 取消确认或业务错误,拦截器已提示
  }
};

onMounted(() => {
  getMyList();
  loadDeptOptions();
});
</script>

<style lang="scss" scoped>
.agent-workspace-page {
  .ws-cards {
    min-height: 120px;
    margin-top: 8px;
  }
  .ws-card {
    margin-bottom: 12px;
    &.is-disabled {
      opacity: 0.65;
    }
    .ws-card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      .ws-name {
        font-weight: 600;
        font-size: 14px;
      }
    }
    .ws-desc {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      min-height: 36px;
      margin-bottom: 8px;
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }
    .ws-meta {
      display: flex;
      gap: 12px;
      align-items: center;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      margin-bottom: 6px;
    }
    .ws-actions {
      border-top: 1px solid var(--el-border-color-lighter);
      padding-top: 6px;
      display: flex;
      gap: 2px;
    }
  }
  .member-toolbar {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
  }
}
</style>
