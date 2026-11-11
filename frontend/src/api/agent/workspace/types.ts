/** 工作空间视图对象 */
export interface WorkspaceVO {
  id: number | string;
  name: string;
  description: string;
  status: string;
  ownerId: number | string;
  ownerName?: string;
  deptId?: number | string;
  deptName?: string;
  memberCount?: number;
  myRole?: string;
  createTime?: string;
  updateTime?: string;
}

/** 工作空间表单对象 */
export interface WorkspaceForm {
  id?: number | string;
  name: string;
  description?: string;
  status?: string;
  keyword?: string;
  deptId?: number | string;
}

/** 工作空间查询参数 */
export interface WorkspaceQuery extends PageQuery {
  keyword?: string;
  status?: string;
  deptId?: number | string;
}

/** 工作空间成员视图对象 */
export interface WorkspaceMemberVO {
  id: number | string;
  workspaceId: number | string;
  userId: number | string;
  userName?: string;
  nickName?: string;
  userStatus?: string;
  role: string;
  roleLabel?: string;
  createTime?: string;
}

/** 成员表单对象 */
export interface MemberForm {
  id?: number | string;
  workspaceId?: number | string;
  userId?: number | string;
  role?: string;
}
