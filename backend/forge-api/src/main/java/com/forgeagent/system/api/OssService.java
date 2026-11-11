package com.forgeagent.system.api;

import com.forgeagent.system.api.domain.OssDTO;

import java.util.List;

/**
 * 通用 OSS服务
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface OssService {

    /**
     * 通过ossId查询对应的url
     *
     * @param ossIds ossId串逗号分隔
     * @return url串逗号分隔
     */
    String selectUrlByIds(String ossIds);

    /**
     * 通过ossId查询列表
     *
     * @param ossIds ossId串逗号分隔
     * @return 列表
     */
    List<OssDTO> selectByIds(String ossIds);
}
