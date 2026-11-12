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
     * 处理提示(向量补偿/扫描件/审核驳回原因等)
     */
    private String notice;

    /**
     * 可见性(R5-1):INHERIT / RESTRICTED
     */
    private String visibility;

    /**
     * 发布态(R5-2):DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED
     */
    private String publishStatus;

    /**
     * 生效时间
     */
    private Date effectiveAt;

    /**
     * 过期时间
     */
    private Date expiresAt;

    /**
     * 回收站(1=已入回收站)
     */
    private Integer trashed;

    /**
     * 入回收站时间
     */
    private Date trashedAt;

    /**
     * 命中当前可见性口径(前端灰显用;列表接口不计算,治理接口按需返回)
     */
    private Boolean visibleNow;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
