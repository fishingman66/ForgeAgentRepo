package com.forgeagent.customerservice.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客服领域三元组维护入参(P3 / M3)。
 *
 * <p>领域知识由运营/开发者人工登记(产品、故障、话术、意图之间的关系),
 * 不做自动脱敏:这里是受控的管理端入口,录入的是领域事实而非访客原文。
 * 实体名与关系的归一化、唯一键去重、权重累加全部由 forge-kg 的
 * {@code KgService} / {@code KgText} 统一处理。</p>
 *
 * @author ForgeAgent
 */
@Data
public class CsKgRelation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主语实体名,2~64 字符 */
    @NotBlank(message = "主语实体不能为空")
    @Size(max = 64, message = "主语实体过长")
    private String subject;

    /** 关系动词短语,如 属于/需要/解决 */
    @NotBlank(message = "关系不能为空")
    @Size(max = 32, message = "关系过长")
    private String relation;

    /** 宾语实体名,2~64 字符 */
    @NotBlank(message = "宾语实体不能为空")
    @Size(max = 64, message = "宾语实体过长")
    private String object;

    /** 溯源引用(FAQ ID / 工单 ID),可空 */
    private Long evidenceRef;
}
