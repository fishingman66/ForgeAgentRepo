package com.forgeagent.agent.domain.vo;

import lombok.Data;

/**
 * 模型连通性测试结果(C3-2 BYO-key)。
 *
 * <p>失败返回 {@code ok=false} 而非 5xx:Key 错误/网络不通属于可预期分支,
 * 前端提示后允许继续保存(避免 Key 临时抖动导致配置无法落库)。</p>
 */
@Data
public class AgentModelTestVo {

    private boolean ok;

    private String message;

    /** 模型回复片段(截断),用于确认真的调通而不是只看状态码 */
    private String sample;
}
