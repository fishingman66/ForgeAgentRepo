/** 供应商视图对象 */
export interface ProviderVO {
  id: string;
  providerCode: string;
  providerName: string;
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 供应商表单对象 */
export interface ProviderForm {
  id?: string;
  providerCode?: string;
  providerName: string;
  status?: string;
  remark?: string;
}

/** 供应商查询参数 */
export interface ProviderQuery extends PageQuery {
  keyword?: string;
  status?: string;
}
