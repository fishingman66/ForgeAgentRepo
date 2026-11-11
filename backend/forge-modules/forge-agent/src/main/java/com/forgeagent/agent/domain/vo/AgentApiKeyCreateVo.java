package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * API Key 创建结果(明文仅此一次返回)
 *
 * @author ForgeAgent
 */
@Data
public class AgentApiKeyCreateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Key 记录ID
     */
    private Long id;

    /**
     * Key 明文(仅此一次返回,请妥善保存)
     */
    private String plainKey;

}
