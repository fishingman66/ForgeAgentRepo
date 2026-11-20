package com.forgeagent.agent.domain.bo;

import lombok.Data;

/**
 * C4 草稿预览入参:直接按 content 渲染,不落版本链。
 */
@Data
public class PromptDraftPreviewBo {

    /** 草稿内容(必填) */
    private String content;

    /** 变量契约 JSON(可选,与模板 variableSchema 同格式) */
    private String variableSchema;

    /** 变量取值 JSON */
    private String variables;
}
