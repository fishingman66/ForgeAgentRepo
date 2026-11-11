package com.forgeagent.rag.domain.vo;

import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
/**
 * 知识库索引重建进度视图对象
 *
 * @author ForgeAgent
 */
@Data
public class KbRebuildProgressVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    /**
     * 文档总数
     */
    private Long total;

    /**
     * 待处理数(状态 0)
     */
    private Long pending;

    /**
     * 已完成数(状态 1)
     */
    private Long success;

    /**
     * 失败数(状态 2)
     */
    private Long failed;

    /**
     * 完成百分比(0-100,失败也计入完成)
     */
    private Integer percent;
}
