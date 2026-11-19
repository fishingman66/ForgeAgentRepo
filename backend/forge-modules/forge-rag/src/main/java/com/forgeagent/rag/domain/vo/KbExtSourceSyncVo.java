package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库外部源同步结果视图对象(R5-4):条目级去重计数
 *
 * @author ForgeAgent
 */
@Data
public class KbExtSourceSyncVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 外部源ID
     */
    private Long sourceId;

    /**
     * 本次新导入条目数
     */
    private Integer imported;

    /**
     * 已导入跳过条目数
     */
    private Integer skipped;

    /**
     * 导入失败条目数
     */
    private Integer failed;

}
