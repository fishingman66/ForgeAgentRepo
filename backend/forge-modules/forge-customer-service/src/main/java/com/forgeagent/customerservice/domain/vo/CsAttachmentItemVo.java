package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话附件条目视图对象(CsAttachmentService.list;过滤 agent_chat_attachment 内部字段)
 *
 * @author ForgeAgent
 */
@Data
public class CsAttachmentItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private String kind;

    private String mediaType;

    private String fileExt;

    private Long fileSize;

    /** 解析状态 */
    private String parseStatus;

    /** 解析摘要 */
    private String summary;
}
