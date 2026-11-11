<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  addSemanticDimension,
  addSemanticMetric,
  addSemanticValue,
  batchAddSemanticValues,
  delSemanticDimension,
  delSemanticMetric,
  delSemanticValue,
  importSemanticDimensions,
  listSemanticDimensions,
  listSemanticMetrics,
  listSemanticValues,
  sampleColumnValues,
  updateSemanticDimension,
  updateSemanticMetric,
  updateSemanticValue
} from '@/api/agent/semantic';
import type {
  SemanticDimensionForm,
  SemanticDimensionVO,
  SemanticMetricForm,
  SemanticMetricVO,
  SemanticValueForm,
  SemanticValueVO
} from '@/api/agent/semantic/types';

const props = defineProps<{ visible: boolean; datasourceId: string; datasourceName: string }>();
const emit = defineEmits<{ (e: 'update:visible', val: boolean): void }>();

const visible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
});

const activeTab = ref('metric');
const loading = ref(false);

// ---------- 指标 ----------
const metrics = ref<SemanticMetricVO[]>([]);
const metricDialog = reactive({ visible: false, title: '新增指标', form: {} as SemanticMetricForm });
const metricRules = {
  name: [{ required: true, message: '指标名不能为空', trigger: 'blur' }],
  baseTable: [{ required: true, message: '基表名不能为空', trigger: 'blur' }],
  expression: [{ required: true, message: '表达式不能为空', trigger: 'blur' }]
};

const loadMetrics = async () => {
  loading.value = true;
  try {
    const res = await listSemanticMetrics(props.datasourceId);
    metrics.value = res.data || [];
  } finally {
    loading.value = false;
  }
};

const openMetricAdd = () => {
  metricDialog.title = '新增指标';
  metricDialog.form = { datasourceId: props.datasourceId, name: '', baseTable: '', expression: '', dimType: undefined } as any;
  metricDialog.visible = true;
};

const openMetricEdit = (row: SemanticMetricVO) => {
  metricDialog.title = '编辑指标';
  metricDialog.form = { ...row };
  metricDialog.visible = true;
};

const submitMetric = async (formEl: any) => {
  await formEl.validate();
  if (metricDialog.form.id) {
    await updateSemanticMetric(metricDialog.form);
  } else {
    await addSemanticMetric(metricDialog.form);
  }
  ElMessage.success('保存成功');
  metricDialog.visible = false;
  loadMetrics();
};

const deleteMetric = async (row: SemanticMetricVO) => {
  await ElMessageBox.confirm(`确认删除指标「${row.name}」吗?`, '提示', { type: 'warning' });
  await delSemanticMetric(row.id);
  ElMessage.success('删除成功');
  loadMetrics();
};

// ---------- 维度 ----------
const dimensions = ref<SemanticDimensionVO[]>([]);
const dimDialog = reactive({ visible: false, title: '新增维度', form: {} as SemanticDimensionForm });
const dimRules = {
  tableName: [{ required: true, message: '表名不能为空', trigger: 'blur' }],
  columnName: [{ required: true, message: '列名不能为空', trigger: 'blur' }],
  displayName: [{ required: true, message: '业务显示名不能为空', trigger: 'blur' }]
};

const loadDimensions = async () => {
  loading.value = true;
  try {
    const res = await listSemanticDimensions(props.datasourceId);
    dimensions.value = res.data || [];
  } finally {
    loading.value = false;
  }
};

const importDimensions = async () => {
  await ElMessageBox.confirm('将从已同步的表结构中批量导入所有列作为维度,已存在的列会跳过。是否继续?', '导入维度', { type: 'info' });
  const res = await importSemanticDimensions(props.datasourceId);
  ElMessage.success(`导入完成,新增 ${res.data} 条维度`);
  loadDimensions();
};

const openDimAdd = () => {
  dimDialog.title = '新增维度';
  dimDialog.form = { datasourceId: props.datasourceId, tableName: '', columnName: '', displayName: '', dimType: 'text' } as any;
  dimDialog.visible = true;
};

const openDimEdit = (row: SemanticDimensionVO) => {
  dimDialog.title = '编辑维度';
  dimDialog.form = { ...row };
  dimDialog.visible = true;
};

const submitDim = async (formEl: any) => {
  await formEl.validate();
  if (dimDialog.form.id) {
    await updateSemanticDimension(dimDialog.form);
  } else {
    await addSemanticDimension(dimDialog.form);
  }
  ElMessage.success('保存成功');
  dimDialog.visible = false;
  loadDimensions();
};

const deleteDim = async (row: SemanticDimensionVO) => {
  await ElMessageBox.confirm(`确认删除维度「${row.displayName}」吗?`, '提示', { type: 'warning' });
  await delSemanticDimension(row.id);
  ElMessage.success('删除成功');
  loadDimensions();
};

// ---------- 值字典 ----------
const values = ref<SemanticValueVO[]>([]);
const valueFilter = reactive({ tableName: '', columnName: '' });
const valueDialog = reactive({ visible: false, title: '新增值', form: {} as SemanticValueForm });
const valueRules = {
  tableName: [{ required: true, message: '表名不能为空', trigger: 'blur' }],
  columnName: [{ required: true, message: '列名不能为空', trigger: 'blur' }],
  rawValue: [{ required: true, message: '真实值不能为空', trigger: 'blur' }],
  displayName: [{ required: true, message: '业务显示名不能为空', trigger: 'blur' }]
};

// 采样弹窗
const sampleDialog = reactive({
  visible: false, tableName: '', columnName: '', loading: false,
  values: [] as string[], selected: [] as string[]
});

const tableColumnOptions = computed(() => {
  const map = new Map<string, Set<string>>();
  for (const d of dimensions.value) {
    if (!map.has(d.tableName)) map.set(d.tableName, new Set());
    map.get(d.tableName)!.add(d.columnName);
  }
  return Array.from(map.entries()).map(([table, cols]) => ({ table, columns: Array.from(cols) }));
});

const loadValues = async () => {
  loading.value = true;
  try {
    const res = await listSemanticValues(props.datasourceId, valueFilter.tableName, valueFilter.columnName);
    values.value = res.data || [];
  } finally {
    loading.value = false;
  }
};

const openValueAdd = () => {
  valueDialog.title = '新增值';
  valueDialog.form = {
    datasourceId: props.datasourceId,
    tableName: valueFilter.tableName,
    columnName: valueFilter.columnName,
    rawValue: '', displayName: ''
  } as any;
  valueDialog.visible = true;
};

const openValueEdit = (row: SemanticValueVO) => {
  valueDialog.title = '编辑值';
  valueDialog.form = { ...row };
  valueDialog.visible = true;
};

const submitValue = async (formEl: any) => {
  await formEl.validate();
  if (valueDialog.form.id) {
    await updateSemanticValue(valueDialog.form);
  } else {
    await addSemanticValue(valueDialog.form);
  }
  ElMessage.success('保存成功');
  valueDialog.visible = false;
  loadValues();
};

const deleteValue = async (row: SemanticValueVO) => {
  await ElMessageBox.confirm(`确认删除值「${row.displayName}」吗?`, '提示', { type: 'warning' });
  await delSemanticValue(row.id);
  ElMessage.success('删除成功');
  loadValues();
};

const openSample = () => {
  if (!valueFilter.tableName || !valueFilter.columnName) {
    ElMessage.warning('请先选择表和列');
    return;
  }
  sampleDialog.tableName = valueFilter.tableName;
  sampleDialog.columnName = valueFilter.columnName;
  sampleDialog.values = [];
  sampleDialog.selected = [];
  sampleDialog.visible = true;
};

const doSample = async () => {
  sampleDialog.loading = true;
  try {
    const res = await sampleColumnValues(props.datasourceId, sampleDialog.tableName, sampleDialog.columnName, 100);
    sampleDialog.values = res.data || [];
    sampleDialog.selected = [...sampleDialog.values];
  } finally {
    sampleDialog.loading = false;
  }
};

const confirmSample = async () => {
  if (sampleDialog.selected.length === 0) {
    ElMessage.warning('请至少选择一个值');
    return;
  }
  const batch = sampleDialog.selected.map((v) => ({
    datasourceId: props.datasourceId,
    tableName: sampleDialog.tableName,
    columnName: sampleDialog.columnName,
    rawValue: v,
    displayName: v
  })) as SemanticValueForm[];
  await batchAddSemanticValues(batch);
  ElMessage.success(`已导入 ${batch.length} 条值`);
  sampleDialog.visible = false;
  loadValues();
};

// ---------- 初始化 ----------
onMounted(() => {
  if (props.visible) {
    loadMetrics();
    loadDimensions();
    loadValues();
  }
});

const handleTabChange = (tab: string) => {
  if (tab === 'metric') loadMetrics();
  else if (tab === 'dimension') loadDimensions();
  else loadValues();
};
</script>

<template>
  <el-drawer v-model="visible" :title="`语义层 - ${props.datasourceName}`" size="820px" destroy-on-close @open="handleTabChange(activeTab)">
    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <!-- 指标 -->
      <el-tab-pane label="指标" name="metric">
        <div class="semantic-toolbar">
          <span class="heading-tip">定义业务指标的口径与表达式,问数时自动注入,避免模型自行猜测</span>
          <el-button type="primary" plain size="small" icon="Plus" @click="openMetricAdd">新增指标</el-button>
        </div>
        <el-table v-loading="loading" border :data="metrics" size="small">
          <el-table-column prop="name" label="指标名" width="120" />
          <el-table-column prop="synonyms" label="同义词" width="140" show-overflow-tooltip />
          <el-table-column prop="baseTable" label="基表" width="120" />
          <el-table-column prop="expression" label="表达式" show-overflow-tooltip />
          <el-table-column prop="defaultFilters" label="默认过滤" width="160" show-overflow-tooltip />
          <el-table-column prop="description" label="口径说明" width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="scope">
              <el-button size="small" text type="primary" @click="openMetricEdit(scope.row as SemanticMetricVO)">编辑</el-button>
              <el-button size="small" text type="danger" @click="deleteMetric(scope.row as SemanticMetricVO)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 维度 -->
      <el-tab-pane label="维度" name="dimension">
        <div class="semantic-toolbar">
          <span class="heading-tip">建立业务名与表列的映射,支持同义词与维度类型</span>
          <div>
            <el-button size="small" icon="Download" @click="importDimensions">从表结构导入</el-button>
            <el-button type="primary" plain size="small" icon="Plus" @click="openDimAdd">新增维度</el-button>
          </div>
        </div>
        <el-table v-loading="loading" border :data="dimensions" size="small">
          <el-table-column prop="displayName" label="业务名" width="120" />
          <el-table-column prop="tableName" label="表" width="120" />
          <el-table-column prop="columnName" label="列" width="140" />
          <el-table-column prop="dimType" label="类型" width="80" />
          <el-table-column prop="synonyms" label="同义词" show-overflow-tooltip />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="scope">
              <el-button size="small" text type="primary" @click="openDimEdit(scope.row as SemanticDimensionVO)">编辑</el-button>
              <el-button size="small" text type="danger" @click="deleteDim(scope.row as SemanticDimensionVO)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 值字典 -->
      <el-tab-pane label="枚举值字典" name="value">
        <div class="semantic-toolbar">
          <span class="heading-tip">枚举值的业务别名映射,如 华东 → E001,解决模型编错字面量导致 0 行的问题</span>
        </div>
        <div class="value-filter">
          <el-select v-model="valueFilter.tableName" placeholder="选择表" clearable size="small" style="width: 160px" @change="valueFilter.columnName = ''">
            <el-option v-for="o in tableColumnOptions" :key="o.table" :label="o.table" :value="o.table" />
          </el-select>
          <el-select v-model="valueFilter.columnName" placeholder="选择列" clearable size="small" style="width: 160px">
            <el-option
              v-for="col in (tableColumnOptions.find((o) => o.table === valueFilter.tableName)?.columns || [])"
              :key="col" :label="col" :value="col"
            />
          </el-select>
          <el-button size="small" @click="loadValues">查询</el-button>
          <el-button size="small" icon="Plus" @click="openValueAdd">新增值</el-button>
          <el-button size="small" icon="Download" @click="openSample">采样列值批量导入</el-button>
        </div>
        <el-table v-loading="loading" border :data="values" size="small">
          <el-table-column prop="tableName" label="表" width="120" />
          <el-table-column prop="columnName" label="列" width="120" />
          <el-table-column prop="rawValue" label="真实值" width="140" />
          <el-table-column prop="displayName" label="业务名" width="120" />
          <el-table-column prop="synonyms" label="别名" show-overflow-tooltip />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="scope">
              <el-button size="small" text type="primary" @click="openValueEdit(scope.row as SemanticValueVO)">编辑</el-button>
              <el-button size="small" text type="danger" @click="deleteValue(scope.row as SemanticValueVO)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 指标编辑 -->
    <el-dialog v-model="metricDialog.visible" :title="metricDialog.title" width="560px" destroy-on-close>
      <el-form ref="metricFormRef" :model="metricDialog.form" :rules="metricRules" label-width="90px">
        <el-form-item label="指标名" prop="name"><el-input v-model="metricDialog.form.name" maxlength="100" /></el-form-item>
        <el-form-item label="同义词"><el-input v-model="metricDialog.form.synonyms" placeholder="逗号分隔,如 GMV,成交额" /></el-form-item>
        <el-form-item label="基表" prop="baseTable"><el-input v-model="metricDialog.form.baseTable" /></el-form-item>
        <el-form-item label="表达式" prop="expression"><el-input v-model="metricDialog.form.expression" placeholder="如 SUM(pay_amount)" /></el-form-item>
        <el-form-item label="默认过滤"><el-input v-model="metricDialog.form.defaultFilters" placeholder="如 pay_status IN ('PAID')" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="metricDialog.form.unit" placeholder="如 元" style="width: 120px" /></el-form-item>
        <el-form-item label="口径说明"><el-input v-model="metricDialog.form.description" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metricDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitMetric($refs.metricFormRef)">确定</el-button>
      </template>
    </el-dialog>

    <!-- 维度编辑 -->
    <el-dialog v-model="dimDialog.visible" :title="dimDialog.title" width="520px" destroy-on-close>
      <el-form ref="dimFormRef" :model="dimDialog.form" :rules="dimRules" label-width="90px">
        <el-form-item label="表名" prop="tableName"><el-input v-model="dimDialog.form.tableName" /></el-form-item>
        <el-form-item label="列名" prop="columnName"><el-input v-model="dimDialog.form.columnName" /></el-form-item>
        <el-form-item label="业务名" prop="displayName"><el-input v-model="dimDialog.form.displayName" /></el-form-item>
        <el-form-item label="同义词"><el-input v-model="dimDialog.form.synonyms" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="dimDialog.form.dimType" style="width: 100%">
            <el-option label="文本" value="text" />
            <el-option label="枚举" value="enum" />
            <el-option label="时间" value="time" />
            <el-option label="ID" value="id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dimDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitDim($refs.dimFormRef)">确定</el-button>
      </template>
    </el-dialog>

    <!-- 值编辑 -->
    <el-dialog v-model="valueDialog.visible" :title="valueDialog.title" width="520px" destroy-on-close>
      <el-form ref="valueFormRef" :model="valueDialog.form" :rules="valueRules" label-width="90px">
        <el-form-item label="表名" prop="tableName"><el-input v-model="valueDialog.form.tableName" /></el-form-item>
        <el-form-item label="列名" prop="columnName"><el-input v-model="valueDialog.form.columnName" /></el-form-item>
        <el-form-item label="真实值" prop="rawValue"><el-input v-model="valueDialog.form.rawValue" /></el-form-item>
        <el-form-item label="业务名" prop="displayName"><el-input v-model="valueDialog.form.displayName" /></el-form-item>
        <el-form-item label="别名"><el-input v-model="valueDialog.form.synonyms" placeholder="逗号分隔" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="valueDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitValue($refs.valueFormRef)">确定</el-button>
      </template>
    </el-dialog>

    <!-- 采样列值 -->
    <el-dialog v-model="sampleDialog.visible" title="采样列值并批量入库" width="480px" destroy-on-close @open="doSample">
      <div class="sample-info">表: {{ sampleDialog.tableName }} · 列: {{ sampleDialog.columnName }}</div>
      <div v-loading="sampleDialog.loading">
        <el-checkbox-group v-model="sampleDialog.selected">
          <div v-for="v in sampleDialog.values" :key="v" class="sample-item">
            <el-checkbox :value="v">{{ v }}</el-checkbox>
          </div>
          <el-empty v-if="!sampleDialog.loading && sampleDialog.values.length === 0" description="该列无 distinct 值" />
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button @click="sampleDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="sampleDialog.loading" @click="confirmSample">导入选中项</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.semantic-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.heading-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.value-filter {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.sample-info {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.sample-item {
  padding: 4px 0;
}
</style>
