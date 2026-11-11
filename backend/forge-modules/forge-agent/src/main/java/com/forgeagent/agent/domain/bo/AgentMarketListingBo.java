package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场条目查询对象(浏览/我的发布/审核队列共用)
 *
 * @author ForgeAgent
 */
@Data
public class AgentMarketListingBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关键字(名称/描述模糊)
     */
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

    /**
     * 分类(general/office/dev/data/life/other)
     */
    @Size(max = 30, groups = {QueryGroup.class})
    private String category;

    /**
     * 状态(审核队列用: 0待审核 1已上架 2已驳回 3已下架)
     */
    private String status;

    /**
     * 排序(new最新/install安装量/rating评分,默认 new)
     */
    @Size(max = 20, groups = {QueryGroup.class})
    private String sort;

}
