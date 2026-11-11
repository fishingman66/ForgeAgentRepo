package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 知识缺口(P1),对应表 {@code agent_cs_knowledge_gap}。
 *
 * <p>按问题 hash 归并同一类缺口,命中次数决定优先级;运营可一键转成待审答案。</p>
 */
@Data
@TableName("agent_cs_knowledge_gap")
public class CsKnowledgeGap {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private String questionHash;
    private String question;
    /** 分类:取自命中的意图编码,无意图为空。 */
    private String category;
    private String priority;
    private Integer hitCount;
    private String status;
    private Long answerId;
    private Date lastHitAt;
    private Date createTime;
    private Date updateTime;
}
