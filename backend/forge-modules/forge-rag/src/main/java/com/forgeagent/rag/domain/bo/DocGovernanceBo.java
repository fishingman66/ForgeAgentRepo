package com.forgeagent.rag.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 文档治理设置(R5-1 可见性 / R5-2 内容治理)
 *
 * @author ForgeAgent
 */
@Data
public class DocGovernanceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 可见性:INHERIT(继承空间) / RESTRICTED(仅授权角色)
     */
    private String visibility;

    /**
     * RESTRICTED 时的授权角色ID列表(全量覆盖)
     */
    private List<Long> roleIds;

    /**
     * 发布态:DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED
     */
    private String publishStatus;

    /**
     * 生效时间(NULL=立即生效;传值才更新)
     */
    private Date effectiveAt;

    /**
     * 过期时间(NULL=不过期;传值才更新)
     */
    private Date expiresAt;

    /**
     * 是否清空生效/过期时间(与上面两个字段二选一,清空走显式标记)
     */
    private Boolean clearTimeWindow;
}
