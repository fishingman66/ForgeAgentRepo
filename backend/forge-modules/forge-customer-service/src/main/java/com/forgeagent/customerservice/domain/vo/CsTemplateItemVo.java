package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客服应答模板条目视图对象(CsAgentTemplateService.templates)
 *
 * @author ForgeAgent
 */
@Data
public class CsTemplateItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String code;

    private String name;

    private String locale;

    private String fallbackReply;

    private String fallbackReplyEn;

    private String handoffPolicy;
}
