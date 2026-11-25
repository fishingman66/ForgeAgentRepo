<script setup lang="ts">
/**
 * 技能管理(P1-1):技能目录 / 技能组 / 坐席技能等级。
 *
 * 坐席技能保存为全量覆盖,并把 skill:rank 串投影回 CsSeat.skills——
 * 老读路径无需迁移即可拿到等级(等级越高,ACD 同分时越优先)。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteCsSkill, deleteCsSkillGroup, getCsSeatSkills, listAdminWorkspaces, listCsSkillGroups,
  listCsSkills, listCsSkillSeats, saveCsSeatSkills, saveCsSkill, saveCsSkillGroup
} from '@/api/customerservice';
import type { CsSkillItem, CsSkillGroupItem, CsSkillSeat } from '@/api/customerservice';

const loading = ref(false);
const workspaces = ref<number[]>([]);
const adminWorkspace = ref<number | null>(null);
const isSuper = ref(false);
const tab = ref('catalog');
const catalog = ref<CsSkillItem[]>([]);
const groups = ref<CsSkillGroupItem[]>([]);
const seats = ref<CsSkillSeat[]>([]);

const dialog = ref(false);
const editing = ref(false);
const kind = ref('skill');
const form = reactive<Record<string, any>>({});

const selectedSeat = ref<number | null>(null);
const seatRanks = reactive<Record<string, number>>({});
const newRankCode = ref('');
const newRankValue = ref(1);

const load = async () => {
  loading.value = true;
  try {
    try {
      const w = await listAdminWorkspaces();
      workspaces.value = w.data ?? [];
      isSuper.value = workspaces.value.length > 0;
    } catch { /* 非超管无候选,忽略 */ }
    const wid = adminWorkspace.value ?? undefined;
    const [s, g, q] = await Promise.all([listCsSkills(wid), listCsSkillGroups(wid), listCsSkillSeats(wid)]);
    catalog.value = s.data ?? [];
    groups.value = g.data ?? [];
    seats.value = q.data ?? [];
    if (selectedSeat.value != null) await loadSeatSkills(selectedSeat.value);
  } catch {
    ElMessage.error('技能配置加载失败');
  } finally {
    loading.value = false;
  }
};

const loadSeatSkills = async (seatId: number) => {
  try {
    const res = await getCsSeatSkills(seatId, adminWorkspace.value ?? undefined);
    for (const key of Object.keys(seatRanks)) delete seatRanks[key];
    for (const row of res.data ?? []) seatRanks[row.skillCode] = row.rank;
  } catch {
    ElMessage.error('坐席技能加载失败');
  }
};

const openCreate = (k: string) => {
  kind.value = k;
  editing.value = false;
  Object.assign(form, k === 'skill'
    ? { skillCode: '', name: '', status: '0' }
    : { groupCode: '', name: '', codes: [], matchType: 'ANY' });
  dialog.value = true;
};

const openEdit = (k: string, row: Record<string, any>) => {
  kind.value = k;
  editing.value = true;
  Object.assign(form, {}, row, k === 'group' ? { codes: String(row.skillCodes ?? '').split(',').filter(Boolean) } : {});
  dialog.value = true;
};

const save = async () => {
  if (kind.value === 'skill' && !String(form.skillCode ?? '').trim()) {
    ElMessage.warning('技能编码不能为空'); return;
  }
  if (kind.value === 'group') {
    if (!String(form.groupCode ?? '').trim()) { ElMessage.warning('组编码不能为空'); return; }
    const codes = (form.codes as string[]) ?? [];
    if (codes.length > 10) { ElMessage.warning('单组技能最多 10 个'); return; }
  }
  try {
    if (kind.value === 'skill') {
      await saveCsSkill({
        id: editing.value ? Number(form.id) : undefined,
        skillCode: String(form.skillCode),
        name: String(form.name ?? ''),
        status: String(form.status ?? '0'),
        workspaceId: adminWorkspace.value ?? undefined
      });
    } else {
      await saveCsSkillGroup({
        groupCode: String(form.groupCode),
        name: String(form.name ?? ''),
        skillCodes: (form.codes as string[]) ?? [],
        matchType: String(form.matchType ?? 'ANY'),
        workspaceId: adminWorkspace.value ?? undefined
      });
    }
    ElMessage.success(editing.value ? '已保存' : '已新增');
    dialog.value = false;
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

const remove = async (k: string, row: CsSkillItem | CsSkillGroupItem) => {
  const label = row.name || (row as CsSkillItem).skillCode || (row as CsSkillGroupItem).groupCode;
  try {
    await ElMessageBox.confirm(`确认删除「${label}」？`, '删除确认', { type: 'warning' });
  } catch { return; }
  try {
    if (k === 'skill') await deleteCsSkill(row.id as number, adminWorkspace.value ?? undefined);
    else await deleteCsSkillGroup(row.id as number, adminWorkspace.value ?? undefined);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败');
  }
};

const onSeatChange = async (id: number) => {
  selectedSeat.value = id;
  await loadSeatSkills(id);
};

const addRank = () => {
  const code = newRankCode.value.trim();
  if (!code) { ElMessage.warning('请选择技能'); return; }
  seatRanks[code] = newRankValue.value;
  newRankCode.value = '';
};

const saveRanks = async () => {
  if (selectedSeat.value == null) { ElMessage.warning('请先选择坐席'); return; }
  try {
    await saveCsSeatSkills(selectedSeat.value, { ...seatRanks }, adminWorkspace.value ?? undefined);
    ElMessage.success('坐席技能已保存');
    await load();
  } catch {
    ElMessage.error('保存失败');
  }
};

onMounted(load);
</script>

<template>
  <div class="cs-page">
    <el-card shadow="never">
      <template #header>
        <div class="cs-header">
          <span>技能管理</span>
          <span v-if="isSuper" class="cs-actions">
            <el-select v-model="adminWorkspace" placeholder="全部空间（只读）" style="width: 180px" clearable @change="load">
              <el-option v-for="w in workspaces" :key="w" :label="`空间 ${w}`" :value="w" />
            </el-select>
          </span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <span class="cs-hint">技能等级在 ACD 同分时优先；保存坐席技能会同步更新坐席标签串</span>

      <el-tabs v-model="tab" v-loading="loading" style="margin-top: 10px">
        <el-tab-pane label="技能目录" name="catalog">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="openCreate('skill')">新增技能</el-button>
          </div>
          <el-table :data="catalog" size="small">
            <el-table-column prop="skillCode" label="编码" width="160" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === '0' ? 'success' : 'info'">
                  {{ row.status === '0' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit('skill', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove('skill', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="技能组" name="groups">
          <div class="cs-actions">
            <el-button type="primary" size="small" @click="openCreate('group')">新增技能组</el-button>
            <span class="cs-hint">单组最多 10 个技能；ANY 任一命中 / ALL 全部命中</span>
          </div>
          <el-table :data="groups" size="small">
            <el-table-column prop="groupCode" label="编码" width="140" />
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column prop="skillCodes" label="技能" min-width="200" show-overflow-tooltip />
            <el-table-column prop="matchType" label="匹配" width="80" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit('group', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove('group', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="坐席技能" name="seats">
          <div class="cs-actions">
            <el-select :model-value="selectedSeat" placeholder="选择坐席" style="width: 220px" @change="onSeatChange">
              <el-option v-for="s in seats" :key="s.id" :label="`坐席 ${s.userId} · ${s.seatGroup || 'default'}`" :value="s.id as number" />
            </el-select>
            <el-button size="small" :disabled="selectedSeat == null" @click="saveRanks">保存技能等级</el-button>
          </div>
          <div v-if="selectedSeat != null" class="cs-actions">
            <el-select v-model="newRankCode" placeholder="选择技能" style="width: 180px">
              <el-option v-for="s in catalog" :key="s.skillCode" :label="s.name || s.skillCode" :value="s.skillCode as string" />
            </el-select>
            <el-input-number v-model="newRankValue" :min="1" :max="99" />
            <el-button size="small" @click="addRank">添加/更新</el-button>
          </div>
          <el-table :data="Object.entries(seatRanks).map(([k, v]) => ({ skillCode: k, rank: v }))" size="small">
            <el-table-column prop="skillCode" label="技能" width="180" />
            <el-table-column prop="rank" label="等级" width="100" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="delete seatRanks[row.skillCode]">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑' : '新增'" width="520px">
      <el-form label-width="90px">
        <template v-if="kind === 'skill'">
          <el-form-item label="技能编码"><el-input v-model="form.skillCode" :disabled="editing" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="状态"><el-switch v-model="form.status" active-value="0" inactive-value="1" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="组编码"><el-input v-model="form.groupCode" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="技能">
            <el-select v-model="form.codes" multiple style="width: 100%">
              <el-option v-for="s in catalog" :key="s.skillCode" :label="s.name || s.skillCode" :value="s.skillCode as string" />
            </el-select>
          </el-form-item>
          <el-form-item label="匹配方向">
            <el-select v-model="form.matchType" style="width: 160px">
              <el-option label="任一命中" value="ANY" />
              <el-option label="全部命中" value="ALL" />
            </el-select>
          </el-form-item>
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
