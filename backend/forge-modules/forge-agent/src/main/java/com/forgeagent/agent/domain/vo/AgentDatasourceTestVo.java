package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据源连通性测试结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentDatasourceTestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 建连 + 一次探活查询的耗时(毫秒)
     */
    private Long elapsedMs;

}
