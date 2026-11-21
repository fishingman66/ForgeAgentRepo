<script setup lang="ts">
/**
 * F2 SaaS 外壳面板:成员邀请 + 品牌外观。独立组件,避免污染 workspace 主列表的类型推断。
 * 由父组件通过 ref.open(row) 唤起。品牌字段走既有 updateWorkspace(Bo 已扩展)。
 */
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { createInvite, listInvites, updateWorkspace, verifyWorkspaceDomain } from '@/api/agent/workspace';
import type { WorkspaceVO } from '@/api/agent/workspace/types';

interface InviteRow { id: number; role: string; maxUses: number; usedCount: number; expiresAt: string }

const visible = ref(false);
const tab = ref('invite');
const loading = ref(false);
const link = ref('');
const invites = ref<InviteRow[]>([]);
interface SaasForm { role: string; maxUses: number; ttlHours: number; logoUrl: string; themeColor: string; helpCenterDomain: string }
const form = ref<SaasForm>({
  role: 'runner', maxUses: 1, ttlHours: 168,
  logoUrl: '', themeColor: '', helpCenterDomain: ''
});
let workspaceId: number | string = 0;
let workspaceName = '';
const domainStatus = ref('');
const verifyingDomain = ref(false);
const domainStatusLabel: Record<string, string> = {
  NONE: '未校验', RESOLVABLE: '可解析', UNRESOLVABLE: '无法解析', INVALID: '格式不合法'
};

const open = async (row: WorkspaceVO) => {
  workspaceId = row.id;
  workspaceName = row.name;
  link.value = '';
  tab.value = 'invite';
  form.value.logoUrl = row.logoUrl || '';
  form.value.themeColor = row.themeColor || '';
  form.value.helpCenterDomain = row.helpCenterDomain || '';
  domainStatus.value = row.domainStatus || '';
  visible.value = true;
  try {
    const res = await listInvites(workspaceId);
    invites.value = res.data || [];
  } catch {
    invites.value = [];
  }
};
defineExpose({ open });

const generate = async () => {
  loading.value = true;
  try {
    const res = await createInvite(workspaceId, form.value.role, form.value.maxUses, form.value.ttlHours);
    const token = (res as unknown as { data?: { token?: string } })?.data?.token;
    link.value = token ? window.location.origin + '/invite/accept?token=' + encodeURIComponent(token) : '';
    ElMessage.success('邀请链接已生成,仅显示一次');
  } catch {
    ElMessage.error('生成失败,请确认当前为空间管理员');
  } finally {
    loading.value = false;
  }
};

const copyLink = async () => {
  try {
    await navigator.clipboard.writeText(link.value);
    ElMessage.success('已复制');
  } catch {
    ElMessage.warning('请手动复制');
  }
};

const verifyDomain = async () => {
  verifyingDomain.value = true;
  try {
    // 先保存当前输入,否则校验的是上一次落库的值
    await updateWorkspace({
      id: workspaceId, name: workspaceName,
      logoUrl: form.value.logoUrl, themeColor: form.value.themeColor,
      helpCenterDomain: form.value.helpCenterDomain
    });
    const res = await verifyWorkspaceDomain(workspaceId);
    domainStatus.value = (res.data as unknown as string) || '';
    ElMessage.success('域名校验完成: ' + (domainStatusLabel[domainStatus.value] || domainStatus.value));
  } catch {
    ElMessage.error('校验失败,请确认当前为空间管理员');
  } finally {
    verifyingDomain.value = false;
  }
};

const saveBrand = async () => {
  loading.value = true;
  try {
    await updateWorkspace({
      id: workspaceId, name: workspaceName,
      logoUrl: form.value.logoUrl, themeColor: form.value.themeColor,
      helpCenterDomain: form.value.helpCenterDomain
    });
    ElMessage.success('品牌设置已保存');
    domainStatus.value = 'NONE';
    visible.value = false;
  } catch {
    ElMessage.error('保存失败');
  } finally {
    loading.value = false;
  }
};
</script>

<template>
  <el-dialog v-model="visible" title="成员邀请与品牌设置" width="560px" destroy-on-close>
    <el-tabs v-model="tab">
      <el-tab-pane label="成员邀请" name="invite">
        <el-alert type="info" :closable="false" show-icon
                  title="链接仅显示一次,请复制发给被邀请人;对方登录后自动加入本空间。" />
        <div class="saas-form">
          <el-select v-model="form.role" style="width: 120px">
            <el-option label="开发者" value="developer" />
            <el-option label="运行者" value="runner" />
          </el-select>
          <el-input-number v-model="form.maxUses" :min="1" :max="100" title="可用次数" />
          <el-input-number v-model="form.ttlHours" :min="1" :max="720" title="有效小时" />
          <el-button type="primary" :loading="loading" @click="generate">生成链接</el-button>
        </div>
        <div v-if="link" class="saas-link">
          <el-input :model-value="link" readonly />
          <el-button size="small" @click="copyLink">复制</el-button>
        </div>
        <div v-if="invites.length" class="saas-list">
          <div v-for="i in invites" :key="i.id" class="saas-row">
            <span>{{ i.role }}</span><span>已用 {{ i.usedCount }}/{{ i.maxUses }}</span><span>{{ i.expiresAt }}</span>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="品牌外观" name="brand">
        <div class="saas-brand-form">
          <label>Logo URL</label><el-input v-model="form.logoUrl" placeholder="https://..." />
          <label>主题色</label><el-input v-model="form.themeColor" placeholder="#409EFF" />
          <label>帮助中心域名</label>
          <div class="saas-domain-row">
            <el-input v-model="form.helpCenterDomain" placeholder="help.example.com" />
            <el-button size="small" :loading="verifyingDomain" @click="verifyDomain">校验</el-button>
          </div>
        </div>
        <div class="saas-tip">
          <span v-if="domainStatus">状态:{{ domainStatusLabel[domainStatus] || domainStatus }}。</span>
          仅校验域名格式与 DNS 可解析性;生效还需自行配置 CNAME 与证书,平台暂不代签发。
        </div>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="tab === 'brand'" type="primary" :loading="loading" @click="saveBrand">保存品牌</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.saas-form { display: flex; gap: 8px; align-items: center; margin: 14px 0; flex-wrap: wrap; }
.saas-link { display: flex; gap: 8px; align-items: center; margin: 10px 0; }
.saas-list { margin-top: 12px; border-top: 1px solid var(--el-border-color-lighter); padding-top: 8px; }
.saas-row { display: flex; gap: 16px; font-size: 12px; color: var(--el-text-color-secondary); padding: 4px 0; }
.saas-brand-form { display: grid; grid-template-columns: 110px 1fr; gap: 10px; align-items: center; }
.saas-domain-row { display: flex; gap: 8px; }
.saas-tip { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
