import type {
  DatasourceExampleForm,
  DatasourceExampleVO,
  DatasourceForm,
  DatasourceQuery,
  DatasourceTableItem,
  DatasourceVO
} from '@/api/agent/datasource/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询问数数据源 */
export const listDatasource = (query: DatasourceQuery): AxiosPromise<PageResult<DatasourceVO>> => {
  return request({
    url: '/agent/datasource/list',
    method: 'get',
    params: query
  });
};

/** 问数数据源详情 */
export const getDatasource = (id: string | number): AxiosPromise<DatasourceVO> => {
  return request({
    url: '/agent/datasource/' + id,
    method: 'get'
  });
};

/** 新增问数数据源 */
export const addDatasource = (data: DatasourceForm) => {
  return request({
    url: '/agent/datasource',
    method: 'post',
    data: data
  });
};

/** 修改问数数据源 */
export const updateDatasource = (data: DatasourceForm) => {
  return request({
    url: '/agent/datasource',
    method: 'put',
    data: data
  });
};

/** 启用/停用问数数据源 */
export const changeDatasourceStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/datasource/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除问数数据源 */
export const delDatasource = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/datasource/' + ids,
    method: 'delete'
  });
};

/** 连接测试(已保存数据源密码留空沿用库内配置) */
export const testDatasource = (data: DatasourceForm): AxiosPromise<{ elapsedMs: number }> => {
  return request({
    url: '/agent/datasource/test',
    method: 'post',
    data: data
  });
};

/** 查看表结构快照 */
export const getDatasourceSchema = (id: string | number): AxiosPromise<string> => {
  return request({
    url: `/agent/datasource/${id}/schema`,
    method: 'get'
  });
};

/** 同步表结构快照 */
export const refreshDatasourceSchema = (id: string | number): AxiosPromise<string> => {
  return request({
    url: `/agent/datasource/${id}/schema`,
    method: 'put'
  });
};

/** 可查询表清单(名称+注释,供白名单选择) */
export const listDatasourceTables = (id: string | number): AxiosPromise<DatasourceTableItem[]> => {
  return request({
    url: `/agent/datasource/${id}/tables`,
    method: 'get'
  });
};

/** 按数据源查询问数示例列表 */
export const listDatasourceExamples = (datasourceId: string | number): AxiosPromise<DatasourceExampleVO[]> => {
  return request({
    url: `/agent/datasource/example/list/${datasourceId}`,
    method: 'get'
  });
};

/** 新增问数示例 */
export const addDatasourceExample = (data: DatasourceExampleForm) => {
  return request({
    url: '/agent/datasource/example',
    method: 'post',
    data: data
  });
};

/** 修改问数示例 */
export const updateDatasourceExample = (data: DatasourceExampleForm) => {
  return request({
    url: '/agent/datasource/example',
    method: 'put',
    data: data
  });
};

/** 删除问数示例 */
export const delDatasourceExample = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/datasource/example/' + ids,
    method: 'delete'
  });
};
