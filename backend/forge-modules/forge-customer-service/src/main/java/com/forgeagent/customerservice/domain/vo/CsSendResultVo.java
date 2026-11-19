package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 访客发送消息统一结果视图对象(CsVisitorService.send/createSession;键全骆驼,字段类型按 put 值实测)。
 * 一个 Vo 承载「复用会话/正常回复/限流拦截/槽位收集/转人工」五种形态的可选字段。
 *
 * @author ForgeAgent
 */
@Data
public class CsSendResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long sessionId;

    private Long conversationId;

    /** 访客令牌(仅 createSession/新会话返回) */
    private String visitorToken;

    private String clientMsgId;

    private Long messageId;

    /** 助手回复正文 */
    private String content;

    /** reused=true 表示命中会话复用,未产生新对话轮 */
    private Boolean reused;

    /** 多渠道兜底应答(仅 fallback 通道) */
    private Boolean fallback;

    /** FAQ 命中 */
    private Boolean faqHit;

    /** 语义缓存命中 */
    private Boolean cached;

    /** 敏感词/内容拦截 */
    private Boolean blocked;

    /** 配额拦截 */
    private Boolean quotaBlocked;

    /** 是否已转人工(ticketId 非空) */
    private Boolean handoff;

    /** 转人工来源(visitor/quota/sensitive 等) */
    private String handoffBy;

    /** 转人工工单号 */
    private Long ticketId;

    /** 槽位收集:表单名 */
    private String slotForm;

    /** 槽位收集:是否完成 */
    private Boolean slotDone;

    /** 槽位收集:已收集项 */
    private Map<String, String> slotCollected;

    /** 槽位收集:触发了槽位流程 */
    private Boolean slotFilling;

    /** 兜底/拦截提示语 */
    private String message;
    /** 受理回执(恒 ACCEPTED,保留契约字段) */
    private String acceptance;

    /** 附件数(纯附件消息) */
    private Integer attachmentCount;

    /** 会话状态(ACTIVE/ENDED) */
    private String status;

    /** 附加明细(保留扩展位) */
    private List<Object> attachments;
}
