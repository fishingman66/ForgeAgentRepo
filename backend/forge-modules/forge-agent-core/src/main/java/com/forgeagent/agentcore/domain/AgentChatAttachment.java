package com.forgeagent.agentcore.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 会话图片附件对象 agent_chat_attachment
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_chat_attachment")
public class AgentChatAttachment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 磁盘存储文件名
     */
    private String storedName;

    /**
     * 原始文件名
     */
    private String originalName;

    /**
     * 媒体类型(image/png、image/jpeg、application/pdf 等)
     */
    private String mediaType;

    /**
     * 附件类型(image图片/file文件)
     */
    private String kind;

    /**
     * 文件扩展名(小写)
     */
    private String fileExt;

    /**
     * 文件内容MD5
     */
    private String fileMd5;

    /**
     * 解析状态(0解析中 1可用 2失败)
     */
    private String parseStatus;

    /**
     * 索引分块数
     */
    private Integer chunkCount;

    /**
     * 全文估算token数
     */
    private Integer tokenCount;

    /**
     * 文件摘要(首段/大纲)
     */
    private String summary;

    /**
     * 解析失败原因
     */
    private String failReason;

    /**
     * 临时索引知识库ID(仅文件)
     */
    private Long knowledgeId;

    /**
     * 页数/工作表数(PDF/Excel)
     */
    private Integer pageCount;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 缩略图(data URI)
     */
    private String thumb;

    /**
     * 所属工作空间ID
     */
    private Long workspaceId;

    /**
     * 所属会话ID
     */
    private Long sessionId;

    /**
     * 所属消息ID
     */
    private Long messageId;

    /**
     * 状态(0正常)
     */
    private String status;

}
