package com.forgeagent.callcenter.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 坐席分机绑定 upsert 业务对象:维护 workNo → 分机映射。
 *
 * <p>workNo 是业务唯一键;seatId 可选,绑到文本域坐席后坐席侧展示与路由可联动。
 * readyState 不由此入口维护(由通话网关按登录/就绪事件回写)。</p>
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallSeatState.class, reverseConvertGenerate = false)
public class CsCallSeatBindingBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(更新已有行时回传)
     */
    private Long id;

    /**
     * 绑定的文本域坐席ID(可选)
     */
    private Long seatId;

    /**
     * 归属工作空间(留空沿用已存值)
     */
    private Long workspaceId;

    /**
     * AICC 工号(业务唯一键)
     */
    @NotBlank(message = "工号不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "工号不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String workNo;

    /**
     * 分机号
     */
    @Size(max = 32, message = "分机号不能超过 32 字符", groups = {AddGroup.class, EditGroup.class})
    private String extension;

}
