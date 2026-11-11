package com.forgeagent.agentcore.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agentcore.domain.AgentChatAttachment;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话图片附件视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentChatAttachment.class)
public class ChatAttachmentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 附件ID
     */
    private Long id;

    /**
     * 原始文件名
     */
    private String name;

    /**
     * 媒体类型
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
     * 页数/工作表数(PDF/Excel)
     */
    private Integer pageCount;

    /**
     * 文件大小(字节)
     */
    private Long size;

    /**
     * 缩略图(data URI)
     */
    private String thumb;

}
