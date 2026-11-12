package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库外部源条目 agent_kb_ext_item(R5-4):去重与增量同步依据
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_ext_item")
public class AgentKbExtItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    /**
     * 外部唯一标识(URL / 对象键)
     */
    private String externalId;

    private String title;

    /**
     * 入库后的文档ID
     */
    private Long documentId;

    /**
     * PENDING / IMPORTED / FAILED / SKIPPED
     */
    private String status;

    private String lastError;

    private Date createTime;
}
