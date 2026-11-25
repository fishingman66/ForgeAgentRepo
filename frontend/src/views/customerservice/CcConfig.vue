<script setup lang="ts">
/**
 * 呼叫中心运营配置(P0~P2):溢出策略 / 号码池 / 黑名单 / 字冠路由 / 企业配额,
 * 加两个只读页签(坐席状态轨迹 / 话单补偿留痕)。
 *
 * 所有接口的服务端都会从当前坐席推导 workspaceId,本页不传也不信任空间参数;
 * 状态日志与话单补偿是事件驱动的留痕表,只提供只读列表。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteCcBlacklist, deleteCcDidRoute, deleteCcNumber, deleteCcOverflow,
  getCcEnterprise, listCcBlacklist, listCcCdrCompensations, listCcDidRoutes,
  listAdminWorkspaces, listCcNumbers, listCcOverflow, listCcStateLogs,
  saveCcBlacklist, saveCcDidRoute, saveCcEnterprise, saveCcNumber, saveCcOverflow
} from '@/api/customerservice';
import type {
  CcBlackPhone, CcDidRoute, CcNumberPool, CcOverflowConfig, CcEnterpriseQuota,
  CcStateLog, CcCdrCompensation
} from '@/api/customerservice';

const tab = ref('overflow');
// 超管候选空间:null=全部空间(只读列表),选中某空间后可编辑该空间配置
const workspaces = ref<number[]>([]);
const adminWorkspace = ref<number | null>(null);
const isSuper = ref(false);
const loading = ref(false);
const dialog = ref(false);
const editing = ref(false);
const kind = ref('overflow');

const overflows = ref<CcOverflowConfig[]>([]);
const numbers = ref<CcNumberPool[]>([]);
const blacklist = ref<CcBlackPhone[]>([]);
const didRoutes = ref<CcDidRoute[]>([]);
const stateLogs = ref<CcStateLog[]>([]);
const compensations = ref<CcCdrCompensation[]>([]);
const enterprise = reactive<CcEnterpriseQuota>({});

const blank = (): Record<string, any> => ({
  name: '', triggerType: 'QUEUE_TIMEOUT', threshold: 60, action: 'HANGUP', actionArg: '', enabled: '0',
  number: '', type: 1, channelCode: '', trunkRef: '', status: '0', description: '',
  numPrefix: '', callDirection: 2, schedule: '', callbackUrl: '', reason: '',
  routeNum: '', target: '', callerPrefix: '', calledPrefix: ''
});
const form = reactive<Record<string, any>>(blank());

const load = async () => {
  loading.value = true;
  try {
    try {
      const w = await listAdminWorkspaces();
      workspaces.value = w.data ?? [];
      isSuper.value = workspaces.value.length > 0;
    } catch { /* 非超管无候选,忽略 */ }
    const wid = adminWorkspace.value ?? undefined;
    const [o, n, b, d, e, s, c] = await Promise.all([
      listCcOverflow(wid), listCcNumbers(wid), listCcBlacklist(wid), listCcDidRoutes(wid),
      getCcEnterprise(wid), listCcStateLogs(wid), listCcCdrCompensations(wid)
    ]);
    overflows.value = o.data ?? [];
    numbers.value = n.data ?? [];
    blacklist.value = b.data ?? [];
    didRoutes.value = d.data ?? [];
    stateLogs.value = s.data ?? [];
    compensations.value = c.data ?? [];
    Object.assign(enterprise, e.data ?? {});
  } catch {
    ElMessage.error('配置加载失败（呼叫中心模块可能未启用）');
  } finally {
    loading.value = false;
  }
};

const openCreate = (k: string) => {
  kind.value = k;
  editing.value = false;
  Object.assign(form, blank());
  dialog.value = true;
};

const openEdit = (k: string, row: Record<string, any>) => {
  kind.value = k;
  editing.value = true;
  Object.assign(form, blank(), row);
  form.enabled = String(form.enabled ?? '0');
  dialog.value = true;
};

const save = async () => {
  if (kind.value === 'overflow' && (!form.name || !form.triggerType)) {
    ElMessage.warning('名称与触发类型不能为空'); return;
  }
  if (kind.value === 'number-pool' && !String(form.number ?? '').trim()) {
    ElMessage.warning('号码不能为空'); return;
  }
  if (kind.value === 'blacklist' && !String(form.numPrefix ?? '').trim()) {
    ElMessage.warning('号码前缀不能为空'); return;
  }
  if (kind.value === 'did-routes' && !String(form.routeNum ?? '').trim()) {
    ElMessage.warning('字冠不能为空'); return;
  }
  try {
    const data = { ...form, id: editing.value ? Number(form.id) : undefined };
    if (kind.value === 'overflow') await saveCcOverflow({ ...data, workspaceId: adminWorkspace.value } as CcOverflowConfig);
    else if (kind.value === 'number-pool') await saveCcNumber({ ...data, workspaceId: adminWorkspace.value } as CcNumberPool);
    else if (kind.value === 'blacklist') await saveCcBlacklist({ ...data, workspaceId: adminWorkspace.value } as CcBlackPhone);
    else await saveCcDidRoute({ ...data, workspaceId: adminWorkspace.value } as CcDidRoute);
    ElMessage.success(editing.value ? '已保存' : '已新增');
    dialog.value = false;
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const remove = async (k: string, row: { id?: number; name?: string; number?: string; numPrefix?: string; routeNum?: string }) => {
  const label = row.name || row.number || row.numPrefix || row.routeNum || '';
  try {
    await ElMessageBox.confirm(`确认删除「${label}」？`, '删除确认', { type: 'warning' });
  } catch { return; }
  try {
    if (k === 'overflow') await deleteCcOverflow(row.id as number, adminWorkspace.value ?? undefined);
    else if (k === 'number-pool') await deleteCcNumber(row.id as number, adminWorkspace.value ?? undefined);
    else if (k === 'blacklist') await deleteCcBlacklist(row.id as number, adminWorkspace.value ?? undefined);
    else await deleteCcDidRoute(row.id as number, adminWorkspace.value ?? undefined);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败');
  }
};

const saveQuota = async () => {
  try {
    await saveCcEnterprise({ ...enterprise, workspaceId: adminWorkspace.value });
    ElMessage.success('配额已保存');
    await load();
  } catch {
    ElMessage.error('配额保存失败');
  }
};

const enabledText = (v: string | undefined) => (v === '0' ? '启用' : '停用');

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header>
        <div class="cs-header">
          <span>呼叫中心运营配置</span>
          <span v-if="isSuper" class="cs-actions">
            <el-select v-model="adminWorkspace" placeholder="全部空间（只读）" style="width: 180px" clearable @change="load">
              <el-option v-for="w in workspaces" :key="w" :label="`空间 ${w}`" :value="w" />
            </el-select>
          </span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <span class="cs-hint">配置按当前坐席所属空间隔离；开关与策略在服务端生效，修改后无需重启</span>

      <el-tabs v-model="tab" v-loading="loading" style="margin-top: 10px">
        <el-tab-pane label="溢出策略" name="overflow">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="openCreate('overflow')">新增策略</el-button>
          </div>
          <el-table :data="overflows" size="small">
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="triggerType" label="触发条件" width="140" />
            <el-table-column prop="threshold" label="阈值" width="80" />
            <el-table-column prop="action" label="动作" width="120" />
            <el-table-column prop="actionArg" label="目标" min-width="120" show-overflow-tooltip />
            <el-table-column label="状态" width="70">
              <template #default="{ row }">{{ enabledText(row.enabled) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit('overflow', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove('overflow', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="号码池" name="number-pool">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="openCreate('number-pool')">新增号码</el-button>
            <span class="cs-hint">仅元数据；无 SIP 资质前不发起真实呼出</span>
          </div>
          <el-table :data="numbers" size="small">
            <el-table-column prop="number" label="号码" min-width="150" />
            <el-table-column prop="type" label="类型" width="90" />
            <el-table-column prop="channelCode" label="渠道" width="120" />
            <el-table-column prop="trunkRef" label="中继引用" min-width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="70">
              <template #default="{ row }">{{ enabledText(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit('number-pool', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove('number-pool', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="黑名单" name="blacklist">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="openCreate('blacklist')">新增前缀</el-button>
            <span class="cs-hint">最长前缀优先；回调地址仅留存，不发起外部请求</span>
          </div>
          <el-table :data="blacklist" size="small">
            <el-table-column prop="numPrefix" label="号码前缀" width="130" />
            <el-table-column prop="callDirection" label="方向" width="80" />
            <el-table-column prop="schedule" label="时段" width="120" />
            <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
            <el-table-column label="状态" width="70">
              <template #default="{ row }">{{ enabledText(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit('blacklist', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove('blacklist', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="字冠路由" name="did-routes">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="openCreate('did-routes')">新增路由</el-button>
            <span class="cs-hint">任意位置包含匹配；* 为兜底；多条按字冠长度降序取首命中</span>
          </div>
          <el-table :data="didRoutes" size="small">
            <el-table-column prop="routeNum" label="字冠" width="100" />
            <el-table-column prop="target" label="目标" width="120" />
            <el-table-column prop="callerPrefix" label="主叫前缀" width="100" />
            <el-table-column prop="calledPrefix" label="被叫前缀" width="100" />
            <el-table-column prop="description" label="说明" min-width="140" show-overflow-tooltip />
            <el-table-column label="状态" width="70">
              <template #default="{ row }">{{ enabledText(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit('did-routes', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove('did-routes', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="企业配额" name="enterprise">
          <div class="cs-actions">
            <span class="cs-hint">配额为 0 会在生产门禁中被拒绝；留空表示不限制</span>
          </div>
          <el-form label-width="110px" style="max-width: 520px">
            <el-form-item label="企业名称">
              <el-input v-model="enterprise.name" placeholder="本空间企业名称" />
            </el-form-item>
            <el-form-item label="坐席上限"><el-input-number v-model="enterprise.seatLimit" :min="0" /></el-form-item>
            <el-form-item label="技能组上限"><el-input-number v-model="enterprise.groupLimit" :min="0" /></el-form-item>
            <el-form-item label="渠道上限"><el-input-number v-model="enterprise.channelLimit" :min="0" /></el-form-item>
            <el-form-item label="并发上限"><el-input-number v-model="enterprise.concurrentLimit" :min="0" /></el-form-item>
            <el-form-item label="月度分钟预算"><el-input-number v-model="enterprise.budgetMinutes" :min="0" /></el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" @click="saveQuota">保存配额</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="状态轨迹" name="state-logs">
          <el-table :data="stateLogs" size="small">
            <el-table-column prop="month" label="归档月" width="100" />
            <el-table-column prop="workNo" label="工号" width="110" />
            <el-table-column prop="beforeState" label="前态" width="120" />
            <el-table-column prop="state" label="当前态" width="120" />
            <el-table-column prop="durationSec" label="持续(秒)" width="100" />
            <el-table-column label="说明" min-width="140">
              <template #default>只读 · 事件驱动写入</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="话单补偿" name="cdr">
          <el-table :data="compensations" size="small">
            <el-table-column prop="callId" label="通话ID" width="100" />
            <el-table-column prop="statDate" label="归属日" width="120" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="retryCount" label="重试次数" width="100" />
            <el-table-column prop="error" label="错误" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑配置' : '新增配置'" width="560px">
      <el-form label-width="100px">
        <template v-if="kind === 'overflow'">
          <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="触发条件">
            <el-select v-model="form.triggerType" style="width: 200px">
              <el-option label="等待超时(秒)" value="QUEUE_TIMEOUT" />
              <el-option label="队列长度" value="QUEUE_SIZE" />
              <el-option label="呼损率(%)" value="ABANDON_RATE" />
            </el-select>
          </el-form-item>
          <el-form-item label="阈值"><el-input-number v-model="form.threshold" :min="1" /></el-form-item>
          <el-form-item label="动作">
            <el-select v-model="form.action" style="width: 200px">
              <el-option label="转队列" value="TRANSFER_QUEUE" />
              <el-option label="挂机" value="HANGUP" />
              <el-option label="仅留痕" value="MARK" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标/原因"><el-input v-model="form.actionArg" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.enabled" active-value="0" inactive-value="1" /></el-form-item>
        </template>
        <template v-else-if="kind === 'number-pool'">
          <el-form-item label="号码"><el-input v-model="form.number" /></el-form-item>
          <el-form-item label="类型">
            <el-select v-model="form.type" style="width: 200px">
              <el-option label="呼入服务号" :value="1" />
              <el-option label="主叫显号" :value="2" />
              <el-option label="被叫显号" :value="3" />
            </el-select>
          </el-form-item>
          <el-form-item label="渠道"><el-input v-model="form.channelCode" /></el-form-item>
          <el-form-item label="中继引用"><el-input v-model="form.trunkRef" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
        </template>
        <template v-else-if="kind === 'blacklist'">
          <el-form-item label="号码前缀"><el-input v-model="form.numPrefix" /></el-form-item>
          <el-form-item label="方向">
            <el-select v-model="form.callDirection" style="width: 200px">
              <el-option label="呼入" :value="0" />
              <el-option label="呼出" :value="1" />
              <el-option label="双向" :value="2" />
            </el-select>
          </el-form-item>
          <el-form-item label="时段"><el-input v-model="form.schedule" placeholder="如 09:00-18:00，空=全天" /></el-form-item>
          <el-form-item label="回调地址"><el-input v-model="form.callbackUrl" placeholder="仅留存，不发起请求" /></el-form-item>
          <el-form-item label="原因"><el-input v-model="form.reason" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="字冠"><el-input v-model="form.routeNum" placeholder="任意位置包含匹配；* 兜底" /></el-form-item>
          <el-form-item label="目标"><el-input v-model="form.target" /></el-form-item>
          <el-form-item label="主叫前缀"><el-input v-model="form.callerPrefix" /></el-form-item>
          <el-form-item label="被叫前缀"><el-input v-model="form.calledPrefix" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">{{ editing ? '保存' : '新增' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-page { padding: 12px; }
.cs-header { display: flex; align-items: center; justify-content: space-between; }
.cs-actions { margin-bottom: 10px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.cs-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-left: 6px; }
</style>
