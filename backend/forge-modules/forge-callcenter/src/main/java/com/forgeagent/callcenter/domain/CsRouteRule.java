package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 分流规则(C5,方案 docs/07 §三 P0-3):双引擎的规则侧配置。
 *
 * <p>匹配语义:按 {@code priority} 升序(数值小者优先)逐条评估,命中即止——
 * 与客服域意图库同一口径,便于运营从文本域平移经验。</p>
 *
 * <p>两道防护字段:{@code phraseWhitelist} 非空时要求文本至少出现其中一个词组
 * (压制正则过匹配,例如「退」不该命中「退休」);{@code maxMatchLength} 限制命中片段长度
 * (防 {@code .*} 一类贪婪量词一口吞掉整段,把判定变成恒真)。</p>
 */
@Data
@TableName("agent_cc_route_rule")
public class CsRouteRule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    /** 规则编码,日志与排障用。 */
    private String ruleCode;

    /** 优先级,数值越小越先匹配。 */
    private Integer priority;

    /** REGEX / LLM_ONLY。 */
    private String matchType;

    /** REGEX 正则;LLM_ONLY 留空。 */
    private String pattern;

    /** 命中后产出的意图码。 */
    private String intentCode;

    /** TRANSFER(转队列) / ANSWER(直接应答) / MARK(仅记录)。 */
    private String action;

    /** TRANSFER=队列名;ANSWER=应答键。 */
    private String actionArg;

    /** 词组白名单,英文逗号分隔;非空时文本至少出现其一才命中。 */
    private String phraseWhitelist;

    /** 量词防护:命中片段上限(字符),超出视为过匹配。 */
    private Integer maxMatchLength;

    /** 0=启用 1=停用。 */
    private String enabled;

    private Long delFlag;

    private Date createTime;

    private Date updateTime;
}
