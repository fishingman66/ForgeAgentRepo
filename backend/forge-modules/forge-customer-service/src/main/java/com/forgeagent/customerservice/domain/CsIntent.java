package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 坐席辅助意图标签(A3),对应表 {@code agent_cs_intent}。
 *
 * <p>workspace_id 为空表示平台级(对所有空间生效),与质检规则同口径;
 * intent_code 是落在推荐记录上的稳定键,运营按它聚合意图分布,
 * intent_name 只承担展示职责——改名不影响历史统计。</p>
 */
@Data
@TableName("agent_cs_intent")
public class CsIntent {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private String intentCode;
    private String intentName;
    /** 关键词,逗号分隔;rule_type=REGEX 时整段视为一条正则。 */
    private String keywords;
    private String ruleType;
    /** 关联知识库 ID,逗号分隔;命中意图后并入检索范围(意图加权)。 */
    private String kbIds;
    private Integer priority;
    private String enabled;
    private Date createTime;
    private Date updateTime;
}
