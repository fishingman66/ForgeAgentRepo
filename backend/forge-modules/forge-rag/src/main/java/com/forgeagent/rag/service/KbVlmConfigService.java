package com.forgeagent.rag.service;

import com.forgeagent.rag.domain.bo.KbVlmConfigBo;
import com.forgeagent.rag.domain.vo.KbVlmConfigVo;

import java.util.Map;

/**
 * 图片识别模型配置服务(按工作空间):保存/读取/连通性测试
 *
 * @author ForgeAgent
 */
public interface KbVlmConfigService {

    /**
     * 读取指定空间的配置(未配置返回 null);effectiveSource 提示当前实际生效来源
     */
    KbVlmConfigVo get(Long workspaceId);

    /**
     * 保存(UPSERT):PLATFORM 校验平台模型存在;CUSTOM 校验地址与模型名;
     * apiKey 留空时沿用已存密文,首次配置必填
     */
    void save(KbVlmConfigBo bo);

    /**
     * 连通性测试:用入参(或已存密文)发一次最小请求,返回 {ok, message, model}
     */
    Map<String, Object> test(KbVlmConfigBo bo);
}
