import type { MemberForm, WorkspaceForm, WorkspaceMemberVO, WorkspaceQuery, WorkspaceVO } from '@/api/agent/workspace/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 我加入的空间列表 */
export const myWorkspaces = (keyword?: string): AxiosPromise<WorkspaceVO[]> => {
  return request({
    url: '/agent/workspace/my',
    method: 'get',
    params: { keyword }
  });
};

/** 分页查询全部空间 */
export const listWorkspace = (query: WorkspaceQuery): AxiosPromise<PageResult<WorkspaceVO>> => {
  return request({
    url: '/agent/workspace/list',
    method: 'get',
    params: query
  });
};

/** 空间详情 */
export const getWorkspace = (id: string | number): AxiosPromise<WorkspaceVO> => {
  return request({
    url: '/agent/workspace/' + id,
    method: 'get'
  });
};

/** 新增空间 */
export const addWorkspace = (data: WorkspaceForm) => {
  return request({
    url: '/agent/workspace',
    method: 'post',
    data: data
  });
};

/** 修改空间 */
export const updateWorkspace = (data: WorkspaceForm) => {
  return request({
    url: '/agent/workspace',
    method: 'put',
    data: data
  });
};

/** 启用/停用空间 */
export const changeWorkspaceStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/workspace/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除空间 */
export const delWorkspace = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/workspace/' + ids,
    method: 'delete'
  });
};

/** 空间成员分页列表 */
export const listMember = (workspaceId: string | number, query?: PageQuery): AxiosPromise<PageResult<WorkspaceMemberVO>> => {
  return request({
    url: '/agent/workspace/member/list',
    method: 'get',
    params: { workspaceId, ...query }
  });
};

/** 可加入空间的候选用户 */
export const listCandidates = (workspaceId: string | number, keyword?: string): AxiosPromise<WorkspaceMemberVO[]> => {
  return request({
    url: '/agent/workspace/member/candidates',
    method: 'get',
    params: { workspaceId, keyword }
  });
};

/** 添加成员 */
export const addMember = (data: MemberForm) => {
  return request({
    url: '/agent/workspace/member',
    method: 'post',
    data: data
  });
};

/** 变更成员角色 */
export const changeMemberRole = (data: MemberForm) => {
  return request({
    url: '/agent/workspace/member/role',
    method: 'put',
    data: data
  });
};

/** 移除成员 */
export const delMember = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/workspace/member/' + ids,
    method: 'delete'
  });
};
