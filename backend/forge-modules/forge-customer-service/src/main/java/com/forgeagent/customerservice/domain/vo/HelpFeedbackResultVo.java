package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

/** 帮助中心反馈结果:成功 / 今日已反馈(去重)。 */
@Data
public class HelpFeedbackResultVo {
    private boolean accepted;
    private String message;
}
