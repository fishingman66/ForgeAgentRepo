package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 客服 badcase(P1),对应表 {@code agent_cs_badcase}。
 *
 * <p>三个出口:采纳为知识(生成待审 FAQ)、标记为评测用例(导出给平台评测集)、忽略。
 * 不在这里自建评测引擎——评测能力在平台侧,客服只负责把样本挑出来。</p>
 */
@Data
@TableName("agent_cs_badcase")
public class CsBadcase {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    /** CSAT_LOW / SEAT_IGNORE / ZERO_HIT / SENSITIVE_HIT */
    private String source;
    private Long conversationId;
    private Long ticketId;
    private Long suggestionId;
    private String question;
    private String answer;
    /** PENDING / ADOPTED_KB / ADOPTED_EVAL / IGNORED */
    private String status;
    private String operator;
    private String note;
    private String exported;
    private Date createTime;
    private Date updateTime;
}
