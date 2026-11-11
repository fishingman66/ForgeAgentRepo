package com.forgeagent.rag.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识库文档视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKnowledgeDocument.class)
public class AgentKnowledgeDocumentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属知识库ID
     */
    private Long knowledgeId;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件扩展名
     */
    private String fileExt;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 解析向量化状态(0处理中 1可用 2失败)
     */
    private String status;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * 配置过期(1=知识库分块/清洗口径已变,待差异重建)
     */
    private Integer configStale;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
