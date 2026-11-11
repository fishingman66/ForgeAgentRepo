/** 问数语义层 API 类型 */

/** 语义指标 */
export interface SemanticMetricVO {
  id: string;
  datasourceId: string;
  name: string;
  synonyms?: string;
  baseTable: string;
  expression: string;
  defaultFilters?: string;
  unit?: string;
  format?: string;
  description?: string;
  sort?: number;
  status?: string;
  createTime?: string;
  updateTime?: string;
}

export interface SemanticMetricForm {
  id?: string;
  datasourceId: string;
  name: string;
  synonyms?: string;
  baseTable: string;
  expression: string;
  defaultFilters?: string;
  unit?: string;
  format?: string;
  description?: string;
  sort?: number;
  status?: string;
}

/** 语义维度 */
export interface SemanticDimensionVO {
  id: string;
  datasourceId: string;
  tableName: string;
  columnName: string;
  displayName: string;
  synonyms?: string;
  dimType?: string;
  isTime?: number;
  timeFormat?: string;
  description?: string;
  sort?: number;
  status?: string;
  createTime?: string;
  updateTime?: string;
}

export interface SemanticDimensionForm {
  id?: string;
  datasourceId: string;
  tableName: string;
  columnName: string;
  displayName: string;
  synonyms?: string;
  dimType?: string;
  isTime?: number;
  timeFormat?: string;
  description?: string;
  sort?: number;
  status?: string;
}

/** 语义枚举值字典 */
export interface SemanticValueVO {
  id: string;
  datasourceId: string;
  tableName: string;
  columnName: string;
  rawValue: string;
  displayName: string;
  synonyms?: string;
  sort?: number;
  status?: string;
  createTime?: string;
  updateTime?: string;
}

export interface SemanticValueForm {
  id?: string;
  datasourceId: string;
  tableName: string;
  columnName: string;
  rawValue: string;
  displayName: string;
  synonyms?: string;
  sort?: number;
  status?: string;
}
