package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 客服敏感词(P0),对应表 {@code agent_cs_sensitive_word}。
 *
 * <p>workspace_id 为空表示平台级词库;severity 决定处置方式:
 * BLOCK 拦截整条消息、MASK 打码后放行、WARN 只记审计。</p>
 */
@Data
@TableName("agent_cs_sensitive_word")
public class CsSensitiveWord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private String word;
    private String severity;
    private String remark;
    private String enabled;
    private Date createTime;
}
