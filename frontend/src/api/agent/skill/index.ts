import type { AgentSkillBindVO, SkillForm, SkillInstallTargetVO, SkillQuery, SkillVO } from '@/api/agent/skill/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/** 分页查询技能包 */
export const listSkill = (query: SkillQuery): AxiosPromise<PageResult<SkillVO>> => {
  return request({
    url: '/agent/skill/list',
    method: 'get',
    params: query
  });
};

/** 技能详情 */
export const getSkill = (id: string | number): AxiosPromise<SkillVO> => {
  return request({
    url: '/agent/skill/' + id,
    method: 'get'
  });
};

/** 新增技能 */
export const addSkill = (data: SkillForm) => {
  return request({
    url: '/agent/skill',
    method: 'post',
    data: data
  });
};

/** 修改技能(内容变更版本自增) */
export const updateSkill = (data: SkillForm) => {
  return request({
    url: '/agent/skill',
    method: 'put',
    data: data
  });
};

/** 启用/停用技能 */
export const changeSkillStatus = (id: string | number, status: string) => {
  return request({
    url: '/agent/skill/status',
    method: 'put',
    params: { id, status }
  });
};

/** 删除技能 */
export const delSkill = (ids: string | number | Array<string | number>) => {
  return request({
    url: '/agent/skill/' + ids,
    method: 'delete'
  });
};

/** 安装技能到 Agent(幂等) */
export const installSkill = (skillId: string | number, agentId: string | number) => {
  return request({
    url: `/agent/skill/${skillId}/install/${agentId}`,
    method: 'post'
  });
};

/** 从 Agent 卸载技能 */
export const uninstallSkill = (skillId: string | number, agentId: string | number) => {
  return request({
    url: `/agent/skill/${skillId}/uninstall/${agentId}`,
    method: 'post'
  });
};

/** 技能视角的安装目标清单(全部已发布应用 + 安装态) */
export const listInstallTargets = (skillId: string | number): AxiosPromise<SkillInstallTargetVO[]> => {
  return request({
    url: `/agent/skill/${skillId}/install-targets`,
    method: 'get'
  });
};

/** 应用视角的已装技能清单(含已停用) */
export const listAgentSkills = (agentId: string | number): AxiosPromise<AgentSkillBindVO[]> => {
  return request({
    url: `/agent/skill/installed/${agentId}`,
    method: 'get'
  });
};
