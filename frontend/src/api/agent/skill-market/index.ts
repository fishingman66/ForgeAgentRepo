import type { PageResult } from '@/api/types';
import type { SkillVO } from '@/api/agent/skill/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * P3 / C5:技能市场(只读发现 + 一键绑定)。
 *
 * 与 `/agent/skill/**` 的区别:市场只出「平台共享」技能,普通登录用户即可浏览,
 * 不需要 `agent:skill:list` 管理权限。
 */
export const listMarketSkill = (params: { keyword?: string; pageNum?: number; pageSize?: number }): AxiosPromise<PageResult<SkillVO>> => {
  return request({
    url: '/agent/skill-market/list',
    method: 'get',
    params: params
  });
};

/** 共享技能详情 */
export const getMarketSkill = (id: string | number): AxiosPromise<SkillVO> => {
  return request({
    url: '/agent/skill-market/' + id,
    method: 'get'
  });
};

/** 绑定到指定 Agent(Agent 归属由服务端写校验) */
export const installMarketSkill = (skillId: string | number, agentId: string | number): AxiosPromise<void> => {
  return request({
    url: `/agent/skill-market/${skillId}/install/${agentId}`,
    method: 'post'
  });
};
