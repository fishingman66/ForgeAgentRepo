package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 语义缓存(P1),对应表 {@code agent_cs_semantic_cache}。
 *
 * <p>分区键是「空间 + 渠道」——同空间同渠道才可能命中，绝不跨渠道复用；
 * 缓存的是出站敏感词过滤之后的答案。</p>
 */
@Data
@TableName("agent_cs_semantic_cache")
public class CsSemanticCache {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private String channelCode;
    private String questionHash;
    private String question;
    private String answer;
    private String intent;
    private Integer hits;
    private Date createTime;
    private Date updateTime;
    private Date lastHitAt;
}
