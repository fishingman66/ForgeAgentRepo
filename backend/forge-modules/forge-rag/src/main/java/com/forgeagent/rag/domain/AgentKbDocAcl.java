package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库文档角色授权 agent_kb_doc_acl(R5-1)
 *
 * <p>文档 visibility=RESTRICTED 时生效:登录用户 roleIds 与本文档 ACL 行求交,
 * 交集为空则该文档的召回全部下推过滤(不召回后过滤,避免侵蚀 topK 与融合偏置)。</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_doc_acl")
public class AgentKbDocAcl implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文档ID
     */
    private Long documentId;

    /**
     * 平台角色ID
     */
    private Long roleId;

    private Date createTime;
}
